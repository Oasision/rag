package com.huangyifei.rag.service;

import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;
import com.huangyifei.rag.model.DocumentVector;
import com.huangyifei.rag.repository.DocumentVectorRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);
    private static final int PDF_BOUNDARY_SCAN_LINES = 3;
    private static final int PDF_BOILERPLATE_MIN_LENGTH = 4;
    private static final int PDF_BOILERPLATE_MAX_LENGTH = 120;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private UsageQuotaService usageQuotaService;

    @Autowired
    private OcrService ocrService;

    @Autowired
    private SpeechTranscriptionService speechTranscriptionService;

    @Value("${file.parsing.chunk-size}")
    private int chunkSize;

    @Value("${file.parsing.parent-chunk-size:1048576}")
    private int parentChunkSize;

    @Value("${file.parsing.buffer-size:8192}")
    private int bufferSize;

    @Value("${file.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;

    @Value("${file.ocr.pdf-render-dpi:300}")
    private float ocrPdfRenderDpi;

    @Value("${file.ocr.pdf-fallback-min-text-length:24}")
    private int ocrPdfFallbackMinTextLength;

    @Value("${file.ocr.pdf-fallback-min-meaningful-text-length:16}")
    private int ocrPdfFallbackMinMeaningfulTextLength;

    @Value("${file.ocr.pdf-force-ocr:false}")
    private boolean ocrPdfForceOcr;

    public ParseService() {
    }

    public void parseAndSave(String fileMd5, InputStream fileStream,
                             String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        parseAndSave(fileMd5, fileStream, null, userId, orgTag, isPublic);
    }

    public void parseAndSave(String fileMd5, InputStream fileStream, String fileName,
                             String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        logger.info("开始解析文件 fileMd5={}, fileName={}, userId={}, orgTag={}, isPublic={}",
                fileMd5, fileName, userId, orgTag, isPublic);

        checkMemoryThreshold();

        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            if (isPdfDocument(bufferedStream)) {
                parsePdfAndSave(fileMd5, bufferedStream, fileName, userId, orgTag, isPublic);
                logger.info("PDF 页级解析完成: fileMd5={}", fileMd5);
                return;
            }

            if (ocrService.isImageFile(fileName)) {
                parseImageAndSave(fileMd5, bufferedStream, fileName, userId, orgTag, isPublic);
                logger.info("OCR 图片解析完成: fileMd5={}", fileMd5);
                return;
            }

            StreamingContentHandler handler = new StreamingContentHandler(fileMd5, userId, orgTag, isPublic);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(bufferedStream, handler, metadata, context);

            logger.info("文档流式解析完成: fileMd5={}", fileMd5);
        } catch (SAXException e) {
            logger.error("文档解析失败: fileMd5={}", fileMd5, e);
            throw new RuntimeException("文档解析失败", e);
        }
    }

    public void parseMediaAndSave(String fileMd5, String fileName,
                                  String userId, String orgTag, boolean isPublic) {
        logger.info("开始解析音视频文件: fileMd5={}, fileName={}, userId={}, orgTag={}, isPublic={}",
                fileMd5, fileName, userId, orgTag, isPublic);

        SpeechTranscriptionService.TranscriptionResult result =
                speechTranscriptionService.transcribeFromStorage(fileMd5, fileName);

        int chunkId = 0;
        if (result.segments() != null && !result.segments().isEmpty()) {
            for (SpeechTranscriptionService.TranscriptSegment segment : result.segments()) {
                if (segment.text() == null || segment.text().isBlank()) {
                    continue;
                }
                List<String> chunks = splitTextIntoChunksWithSemantics(segment.text(), chunkSize);
                for (String chunk : chunks) {
                    chunkId++;
                    saveMediaChunk(fileMd5, chunkId, chunk, segment.startTimeMs(), segment.endTimeMs(),
                            result.modelVersion(), userId, orgTag, isPublic);
                }
            }
        }

        if (chunkId == 0 && result.text() != null && !result.text().isBlank()) {
            List<String> chunks = splitTextIntoChunksWithSemantics(result.text(), chunkSize);
            for (String chunk : chunks) {
                chunkId++;
                saveMediaChunk(fileMd5, chunkId, chunk, -1, -1,
                        result.modelVersion(), userId, orgTag, isPublic);
            }
        }

        if (chunkId == 0) {
            logger.warn("音视频转写未产生可入库文件 fileMd5={}, fileName={}", fileMd5, fileName);
        } else {
            logger.info("音视频转写文本保存完成 fileMd5={}, chunkCount={}", fileMd5, chunkId);
        }
    }

    public void parseAndSave(String fileMd5, InputStream fileStream) throws IOException, TikaException {
        parseAndSave(fileMd5, fileStream, null, "unknown", "DEFAULT", false);
    }

    public EmbeddingEstimate estimateEmbeddingUsage(InputStream fileStream) throws IOException, TikaException {
        return estimateEmbeddingUsage(fileStream, null);
    }

    public EmbeddingEstimate estimateEmbeddingUsage(InputStream fileStream, String fileName) throws IOException, TikaException {
        logger.info("转换出错 Embedding Token: fileName={}", fileName);
        checkMemoryThreshold();

        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            if (isPdfDocument(bufferedStream)) {
                return estimatePdfEmbeddingUsage(bufferedStream, fileName);
            }

            if (ocrService.isImageFile(fileName)) {
                return estimateImageEmbeddingUsage(bufferedStream, fileName);
            }

            StreamingEstimateHandler handler = new StreamingEstimateHandler();
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(bufferedStream, handler, metadata, context);
            return handler.snapshot();
        } catch (SAXException e) {
            logger.error("文档 Embedding Token 估算失败", e);
            throw new RuntimeException("文档 Embedding Token 估算失败", e);
        }
    }

    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double memoryUsage = (double) usedMemory / maxMemory;
        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("内存使用率超过 {:.2f}%, 触发 GC", memoryUsage * 100);
            System.gc();

            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;
            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理大文件，当前使用率: " + String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }

    private class StreamingContentHandler extends BodyContentHandler {
        private final StringBuilder buffer = new StringBuilder();
        private final String fileMd5;
        private final String userId;
        private final String orgTag;
        private final boolean isPublic;
        private int savedChunkCount = 0;

        private StreamingContentHandler(String fileMd5, String userId, String orgTag, boolean isPublic) {
            super(-1);
            this.fileMd5 = fileMd5;
            this.userId = userId;
            this.orgTag = orgTag;
            this.isPublic = isPublic;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
            if (buffer.length() >= parentChunkSize) {
                processParentChunk();
            }
        }

        @Override
        public void endDocument() {
            if (buffer.length() > 0) {
                processParentChunk();
            }
        }

        private void processParentChunk() {
            List<String> childChunks = splitTextIntoChunksWithSemantics(buffer.toString(), chunkSize);
            savedChunkCount = saveChildChunks(fileMd5, childChunks, userId, orgTag, isPublic, savedChunkCount, null);
            buffer.setLength(0);
        }
    }

    private class StreamingEstimateHandler extends BodyContentHandler {
        private final StringBuilder buffer = new StringBuilder();
        private long estimatedTokens = 0L;
        private int estimatedChunkCount = 0;

        private StreamingEstimateHandler() {
            super(-1);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
            if (buffer.length() >= parentChunkSize) {
                processParentChunk();
            }
        }

        @Override
        public void endDocument() {
            if (buffer.length() > 0) {
                processParentChunk();
            }
        }

        private void processParentChunk() {
            List<String> childChunks = splitTextIntoChunksWithSemantics(buffer.toString(), chunkSize);
            estimatedChunkCount += childChunks.size();
            estimatedTokens += usageQuotaService.estimateEmbeddingTokens(childChunks);
            buffer.setLength(0);
        }

        private EmbeddingEstimate snapshot() {
            return new EmbeddingEstimate(estimatedTokens, estimatedChunkCount);
        }
    }

    private int saveChildChunks(String fileMd5, List<String> chunks,
                                String userId, String orgTag, boolean isPublic, int startingChunkId, Integer pageNumber) {
        int currentChunkId = startingChunkId;
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }

            currentChunkId++;
            DocumentVector vector = new DocumentVector();
            vector.setFileMd5(fileMd5);
            vector.setChunkId(currentChunkId);
            vector.setTextContent(chunk);
            vector.setPageNumber(pageNumber);
            vector.setAnchorText(buildAnchorText(chunk));
            vector.setUserId(userId);
            vector.setOrgTag(orgTag);
            vector.setPublic(isPublic);
            documentVectorRepository.save(vector);
        }
        logger.info("成功保存 {} 个子切片到数据库", Math.max(0, currentChunkId - startingChunkId));
        return currentChunkId;
    }

    private void saveMediaChunk(String fileMd5, int chunkId, String text, long startTimeMs, long endTimeMs,
                                String modelVersion, String userId, String orgTag, boolean isPublic) {
        if (text == null || text.isBlank()) {
            return;
        }

        DocumentVector vector = new DocumentVector();
        vector.setFileMd5(fileMd5);
        vector.setChunkId(chunkId);
        vector.setTextContent(text);
        vector.setPageNumber(null);
        vector.setAnchorText(buildMediaAnchorText(text, startTimeMs, endTimeMs));
        vector.setModelVersion(modelVersion);
        vector.setUserId(userId);
        vector.setOrgTag(orgTag);
        vector.setPublic(isPublic);
        documentVectorRepository.save(vector);
    }

    private String buildMediaAnchorText(String text, long startTimeMs, long endTimeMs) {
        String normalized = buildAnchorText(text);
        if (startTimeMs < 0) {
            return normalized;
        }

        String timeRange = formatTimestamp(startTimeMs);
        if (endTimeMs >= 0) {
            timeRange += "-" + formatTimestamp(endTimeMs);
        }
        return "[" + timeRange + "] " + (normalized == null ? "" : normalized);
    }

    private String formatTimestamp(long millis) {
        long totalSeconds = Math.max(0, millis / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    private void parsePdfAndSave(String fileMd5, InputStream fileStream, String fileName,
                                 String userId, String orgTag, boolean isPublic) throws IOException {
        try (PDDocument document = PDDocument.load(fileStream)) {
            int savedChunkCount = 0;
            List<String> cleanedPageTexts = extractCleanPdfPageTexts(document, fileName);
            for (int pageNumber = 1; pageNumber <= cleanedPageTexts.size(); pageNumber++) {
                String pageText = cleanedPageTexts.get(pageNumber - 1);
                if (pageText == null || pageText.isBlank()) {
                    continue;
                }
                List<String> childChunks = splitTextIntoChunksWithSemantics(pageText, chunkSize);
                savedChunkCount = saveChildChunks(fileMd5, childChunks, userId, orgTag, isPublic, savedChunkCount, pageNumber);
            }
        }
    }

    private void parseImageAndSave(String fileMd5, InputStream fileStream, String fileName,
                                   String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        String imageText = ocrService.extractTextFromImage(fileStream, fileName == null ? fileMd5 : fileName);
        if (imageText == null || imageText.isBlank()) {
            logger.warn("图片 OCR 未提取到文本: fileMd5={}, fileName={}", fileMd5, fileName);
            return;
        }

        List<String> childChunks = splitTextIntoChunksWithSemantics(imageText, chunkSize);
        saveChildChunks(fileMd5, childChunks, userId, orgTag, isPublic, 0, 1);
    }

    private EmbeddingEstimate estimatePdfEmbeddingUsage(InputStream fileStream, String fileName) throws IOException {
        try (PDDocument document = PDDocument.load(fileStream)) {
            long estimatedTokens = 0L;
            int estimatedChunkCount = 0;

            List<String> cleanedPageTexts = extractCleanPdfPageTexts(document, fileName);
            for (String pageText : cleanedPageTexts) {
                if (pageText == null || pageText.isBlank()) {
                    continue;
                }

                List<String> childChunks = splitTextIntoChunksWithSemantics(pageText, chunkSize);
                estimatedChunkCount += childChunks.size();
                estimatedTokens += usageQuotaService.estimateEmbeddingTokens(childChunks);
            }

            return new EmbeddingEstimate(estimatedTokens, estimatedChunkCount);
        }
    }

    private EmbeddingEstimate estimateImageEmbeddingUsage(InputStream fileStream, String fileName) throws IOException, TikaException {
        String imageText = ocrService.extractTextFromImage(fileStream, fileName == null ? "image" : fileName);
        if (imageText == null || imageText.isBlank()) {
            return new EmbeddingEstimate(0, 0);
        }

        List<String> childChunks = splitTextIntoChunksWithSemantics(imageText, chunkSize);
        return new EmbeddingEstimate(usageQuotaService.estimateEmbeddingTokens(childChunks), childChunks.size());
    }

    private List<String> extractCleanPdfPageTexts(PDDocument document, String fileName) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        List<List<String>> rawPageLines = new ArrayList<>();
        boolean ocrReady = ocrService.canProcessImageFiles();
        PDFRenderer renderer = ocrReady ? new PDFRenderer(document) : null;

        for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            String pageText = stripper.getText(document);

            if (ocrPdfForceOcr) {
                if (ocrReady) {
                    String ocrText = extractPdfPageTextWithOcr(renderer, pageNumber, fileName);
                    if (ocrText != null && !ocrText.isBlank()) {
                        logger.info("PDF 页面强制 OCR: fileName={}, pageNumber={}, rawTextLength={}, ocrTextLength={}",
                                fileName, pageNumber, normalizedVisibleLength(pageText), ocrText.length());
                        pageText = ocrText;
                    } else {
                        logger.warn("PDF 页面强制 OCR 未识别到文本，保留可复制文本: fileName={}, pageNumber={}", fileName, pageNumber);
                    }
                } else {
                    logger.warn("PDF 页面强制 OCR 已开启但 OCR 不可用，保留可复制文本: fileName={}, pageNumber={}", fileName, pageNumber);
                }
            } else if (shouldFallbackToPdfOcr(pageText)) {
                if (ocrReady) {
                    String ocrText = extractPdfPageTextWithOcr(renderer, pageNumber, fileName);
                    if (ocrText != null && !ocrText.isBlank()) {
                        logger.info("PDF 页面触发 OCR fallback: fileName={}, pageNumber={}, rawTextLength={}, ocrTextLength={}",
                                fileName, pageNumber, normalizedVisibleLength(pageText), ocrText.length());
                        pageText = ocrText;
                    }
                } else {
                    logger.warn("PDF 页面文本过少 OCR 不可用 fileName={}, pageNumber={}", fileName, pageNumber);
                }
            }

            rawPageLines.add(splitPdfLines(pageText));
        }

        Map<String, Integer> topLineCounts = collectBoundaryLineCounts(rawPageLines, true);
        Map<String, Integer> bottomLineCounts = collectBoundaryLineCounts(rawPageLines, false);
        int repeatedThreshold = Math.max(2, Math.min(3, document.getNumberOfPages()));

        List<String> cleanedPages = new ArrayList<>(rawPageLines.size());
        for (List<String> rawPageLine : rawPageLines) {
            List<String> cleanedLines = removePdfBoilerplateLines(rawPageLine, topLineCounts, bottomLineCounts, repeatedThreshold);
            cleanedPages.add(String.join("\n", cleanedLines).trim());
        }

        return cleanedPages;
    }

    private String extractPdfPageTextWithOcr(PDFRenderer renderer, int pageNumber, String fileName) throws IOException {
        if (renderer == null) {
            return "";
        }

        try {
            BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, ocrPdfRenderDpi);
            return ocrService.extractTextFromImage(image, (fileName == null ? "pdf" : fileName) + "#page=" + pageNumber);
        } catch (TikaException e) {
            throw new IOException("PDF 椤甸潰 OCR 澶辫触", e);
        }
    }

    private boolean shouldFallbackToPdfOcr(String pageText) {
        return normalizedVisibleLength(pageText) < ocrPdfFallbackMinTextLength
                || normalizedMeaningfulTextLength(pageText) < ocrPdfFallbackMinMeaningfulTextLength;
    }

    private int normalizedVisibleLength(String pageText) {
        if (pageText == null || pageText.isBlank()) {
            return 0;
        }
        return pageText.replaceAll("\\s+", "").length();
    }

    private int normalizedMeaningfulTextLength(String pageText) {
        if (pageText == null || pageText.isBlank()) {
            return 0;
        }

        return (int) pageText.codePoints()
                .filter(Character::isLetter)
                .count();
    }

    private List<String> splitPdfLines(String pageText) {
        if (pageText == null || pageText.isBlank()) {
            return new ArrayList<>();
        }

        String[] lines = pageText.split("\\R");
        List<String> result = new ArrayList<>(lines.length);
        for (String line : lines) {
            result.add(line == null ? "" : line.strip());
        }
        return result;
    }

    private Map<String, Integer> collectBoundaryLineCounts(List<List<String>> pageLines, boolean topBoundary) {
        Map<String, Integer> counts = new HashMap<>();

        for (List<String> lines : pageLines) {
            List<String> boundaryLines = topBoundary
                    ? firstMeaningfulLines(lines, PDF_BOUNDARY_SCAN_LINES)
                    : lastMeaningfulLines(lines, PDF_BOUNDARY_SCAN_LINES);

            for (String line : boundaryLines) {
                String key = normalizePdfBoundaryLine(line);
                if (key != null) {
                    counts.merge(key, 1, Integer::sum);
                }
            }
        }

        return counts;
    }

    private List<String> firstMeaningfulLines(List<String> lines, int maxCount) {
        List<String> result = new ArrayList<>(maxCount);
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            result.add(line);
            if (result.size() >= maxCount) {
                break;
            }
        }
        return result;
    }

    private List<String> lastMeaningfulLines(List<String> lines, int maxCount) {
        List<String> result = new ArrayList<>(maxCount);
        for (int index = lines.size() - 1; index >= 0; index--) {
            String line = lines.get(index);
            if (line == null || line.isBlank()) {
                continue;
            }
            result.add(0, line);
            if (result.size() >= maxCount) {
                break;
            }
        }
        return result;
    }

    private List<String> removePdfBoilerplateLines(
            List<String> lines,
            Map<String, Integer> topLineCounts,
            Map<String, Integer> bottomLineCounts,
            int repeatedThreshold) {

        int start = 0;
        int remainingTopChecks = PDF_BOUNDARY_SCAN_LINES;
        while (start < lines.size() && remainingTopChecks > 0) {
            String line = lines.get(start);
            if (line == null || line.isBlank()) {
                start++;
                continue;
            }

            String key = normalizePdfBoundaryLine(line);
            if (key == null || topLineCounts.getOrDefault(key, 0) < repeatedThreshold) {
                break;
            }

            logger.debug("过滤 PDF 页眉文本: {}", line);
            start++;
            remainingTopChecks--;
        }

        int end = lines.size() - 1;
        int remainingBottomChecks = PDF_BOUNDARY_SCAN_LINES;
        while (end >= start && remainingBottomChecks > 0) {
            String line = lines.get(end);
            if (line == null || line.isBlank()) {
                end--;
                continue;
            }

            String key = normalizePdfBoundaryLine(line);
            if (key == null || bottomLineCounts.getOrDefault(key, 0) < repeatedThreshold) {
                break;
            }

            logger.debug("过滤 PDF 页脚文本: {}", line);
            end--;
            remainingBottomChecks--;
        }

        List<String> cleanedLines = new ArrayList<>();
        for (int index = start; index <= end; index++) {
            cleanedLines.add(lines.get(index));
        }
        return cleanedLines;
    }

    private String normalizePdfBoundaryLine(String line) {
        if (line == null) {
            return null;
        }

        String normalized = Normalizer.normalize(line, Normalizer.Form.NFKC)
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .replaceAll("\\d+", "#")
                .trim()
                .toLowerCase(Locale.ROOT);

        if (normalized.length() < PDF_BOILERPLATE_MIN_LENGTH || normalized.length() > PDF_BOILERPLATE_MAX_LENGTH) {
            return null;
        }

        return normalized;
    }

    private boolean isPdfDocument(BufferedInputStream stream) throws IOException {
        stream.mark(bufferSize);
        byte[] header = stream.readNBytes(5);
        stream.reset();
        return header.length == 5 && "%PDF-".equals(new String(header, StandardCharsets.US_ASCII));
    }

    private String buildAnchorText(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return null;
        }

        String normalized = chunk.replaceAll("\\s+", " ").trim();
        int maxLength = 120;
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n\n+");
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (paragraph.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }

                List<String> sentenceChunks = splitLongParagraph(paragraph, chunkSize);
                chunks.addAll(sentenceChunks);
            } else if (currentChunk.length() + paragraph.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                }
                currentChunk = new StringBuilder(paragraph);
            } else {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(paragraph);
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private List<String> splitLongParagraph(String paragraph, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = paragraph.split("(?<=[銆傦紒锛燂紱])|(?<=[.!?;])\\s+");
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }

                if (sentence.length() > chunkSize) {
                    chunks.addAll(splitLongSentence(sentence, chunkSize));
                } else {
                    currentChunk.append(sentence);
                }
            } else {
                currentChunk.append(sentence);
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private List<String> splitLongSentence(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        try {
            List<Term> termList = StandardTokenizer.segment(sentence);
            StringBuilder currentChunk = new StringBuilder();
            for (Term term : termList) {
                String word = term.word;
                if (currentChunk.length() + word.length() > chunkSize && currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }
                currentChunk.append(word);
            }

            if (currentChunk.length() > 0) {
                chunks.add(currentChunk.toString());
            }

            logger.debug("HanLP 分词成功: sourceLength={}, termCount={}, chunkCount={}",
                    sentence.length(), termList.size(), chunks.size());
        } catch (Exception e) {
            logger.warn("HanLP 分词异常: {}, 使用字符分割作为备用方案", e.getMessage());
            chunks = splitByCharacters(sentence, chunkSize);
        }

        return chunks;
    }

    private List<String> splitByCharacters(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);
            if (currentChunk.length() + 1 > chunkSize && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }
            currentChunk.append(c);
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    public record EmbeddingEstimate(long estimatedTokens, int estimatedChunkCount) {
    }
}
