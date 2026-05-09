package com.huangyifei.rag.service;

import com.huangyifei.rag.client.EmbeddingClient;
import com.huangyifei.rag.client.VisionEmbeddingClient;
import com.huangyifei.rag.entity.EsDocument;
import com.huangyifei.rag.entity.TextChunk;
import com.huangyifei.rag.model.DocumentVector;
import com.huangyifei.rag.model.FileUpload;
import com.huangyifei.rag.repository.DocumentVectorRepository;
import com.huangyifei.rag.repository.FileUploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);
    private static final int IMAGE_SUMMARY_MAX_LENGTH = 600;

    private final EmbeddingClient embeddingClient;
    private final VisionEmbeddingClient visionEmbeddingClient;
    private final ElasticsearchService elasticsearchService;
    private final DocumentVectorRepository documentVectorRepository;
    private final FileUploadRepository fileUploadRepository;
    private final MultimodalAssetService multimodalAssetService;
    private final OcrService ocrService;
    private final UsageQuotaService usageQuotaService;

    public VectorizationService(
            EmbeddingClient embeddingClient,
            VisionEmbeddingClient visionEmbeddingClient,
            ElasticsearchService elasticsearchService,
            DocumentVectorRepository documentVectorRepository,
            FileUploadRepository fileUploadRepository,
            MultimodalAssetService multimodalAssetService,
            OcrService ocrService,
            UsageQuotaService usageQuotaService
    ) {
        this.embeddingClient = embeddingClient;
        this.visionEmbeddingClient = visionEmbeddingClient;
        this.elasticsearchService = elasticsearchService;
        this.documentVectorRepository = documentVectorRepository;
        this.fileUploadRepository = fileUploadRepository;
        this.multimodalAssetService = multimodalAssetService;
        this.ocrService = ocrService;
        this.usageQuotaService = usageQuotaService;
    }

    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, userId);
    }

    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, requesterId);
    }

    public VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        try {
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5).orElse(null);
            boolean imageFile = fileUpload != null && ocrService.isImageFile(fileUpload.getFileName());

            List<TextChunk> chunks = fetchTextChunks(fileMd5);
            updateEstimatedEmbeddingUsage(fileMd5, chunks);
            List<EsDocument> esDocuments = new ArrayList<>();
            int actualEmbeddingTokens = 0;
            StringBuilder modelVersionBuilder = new StringBuilder();

            if (!chunks.isEmpty()) {
                List<String> texts = chunks.stream().map(TextChunk::getContent).toList();
                EmbeddingClient.EmbeddingUsageResult embeddingResult =
                        embeddingClient.embedWithUsage(texts, requesterId, EmbeddingClient.UsageType.UPLOAD);
                List<float[]> vectors = embeddingResult.vectors();
                esDocuments.addAll(IntStream.range(0, chunks.size())
                        .mapToObj(i -> new EsDocument(
                                UUID.randomUUID().toString(),
                                fileMd5,
                                chunks.get(i).getChunkId(),
                                chunks.get(i).getContent(),
                                chunks.get(i).getPageNumber(),
                                chunks.get(i).getAnchorText(),
                                vectors.get(i),
                                null,
                                embeddingResult.modelVersion(),
                                userId,
                                orgTag,
                                isPublic,
                                EsDocument.CONTENT_TYPE_TEXT))
                        .toList());
                actualEmbeddingTokens += embeddingResult.totalTokens();
                modelVersionBuilder.append(embeddingResult.modelVersion());
            }

            if (imageFile) {
                MultimodalAssetService.ImageAsset imageAsset = multimodalAssetService.loadImageAsset(fileMd5);
                VisionEmbeddingClient.EmbeddingUsageResult visionResult =
                        visionEmbeddingClient.embedImage(imageAsset.bytes(), imageAsset.mimeType(), requesterId, VisionEmbeddingClient.UsageType.UPLOAD);
                float[] visionVector = visionResult.vectors().isEmpty() ? null : visionResult.vectors().get(0);
                if (visionVector != null) {
                    esDocuments.add(new EsDocument(
                            UUID.randomUUID().toString(),
                            fileMd5,
                            0,
                            buildImageSummary(fileUpload, chunks),
                            1,
                            fileUpload != null ? fileUpload.getFileName() : null,
                            null,
                            visionVector,
                            visionResult.modelVersion(),
                            userId,
                            orgTag,
                            isPublic,
                            EsDocument.CONTENT_TYPE_IMAGE));
                    actualEmbeddingTokens += visionResult.totalTokens();
                    if (!modelVersionBuilder.isEmpty()) {
                        modelVersionBuilder.append(" | ");
                    }
                    modelVersionBuilder.append(visionResult.modelVersion());
                }
            }

            if (esDocuments.isEmpty()) {
                String fallbackVersion = imageFile ? visionEmbeddingClient.currentModelVersion() : embeddingClient.currentModelVersion();
                return new VectorizationUsageResult(0, 0, fallbackVersion);
            }

            elasticsearchService.bulkIndex(esDocuments);
            return new VectorizationUsageResult(
                    actualEmbeddingTokens,
                    esDocuments.size(),
                    modelVersionBuilder.isEmpty() ? embeddingClient.currentModelVersion() : modelVersionBuilder.toString());
        } catch (Exception exception) {
            logger.error("Vectorization failed for fileMd5={}", fileMd5, exception);
            throw new RuntimeException("Vectorization failed: " + exception.getMessage(), exception);
        }
    }

    private List<TextChunk> fetchTextChunks(String fileMd5) {
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5);
        return vectors.stream()
                .map(vector -> new TextChunk(vector.getChunkId(), vector.getTextContent(), vector.getPageNumber(), vector.getAnchorText()))
                .toList();
    }

    private void updateEstimatedEmbeddingUsage(String fileMd5, List<TextChunk> chunks) {
        List<FileUpload> files = fileUploadRepository.findAllByFileMd5(fileMd5);
        if (files.isEmpty()) {
            return;
        }

        List<String> texts = chunks == null
                ? List.of()
                : chunks.stream()
                .map(TextChunk::getContent)
                .filter(content -> content != null && !content.isBlank())
                .toList();
        long estimatedTokens = usageQuotaService.estimateEmbeddingTokens(texts);
        int estimatedChunkCount = chunks == null ? 0 : chunks.size();

        for (FileUpload file : files) {
            file.setEstimatedEmbeddingTokens(estimatedTokens);
            file.setEstimatedChunkCount(estimatedChunkCount);
        }
        fileUploadRepository.saveAll(files);
    }

    private String buildImageSummary(FileUpload fileUpload, List<TextChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        if (fileUpload != null && fileUpload.getFileName() != null) {
            builder.append("图片文件: ").append(fileUpload.getFileName()).append('\n');
        }
        if (chunks != null && !chunks.isEmpty()) {
            builder.append("OCR 文本:");
            for (TextChunk chunk : chunks) {
                if (chunk.getContent() == null || chunk.getContent().isBlank()) {
                    continue;
                }
                if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                    builder.append('\n');
                }
                builder.append(chunk.getContent().trim());
                if (builder.length() >= IMAGE_SUMMARY_MAX_LENGTH) {
                    break;
                }
            }
        }
        String summary = builder.toString().trim();
        if (summary.length() > IMAGE_SUMMARY_MAX_LENGTH) {
            return summary.substring(0, IMAGE_SUMMARY_MAX_LENGTH) + "...";
        }
        return summary;
    }

    public record VectorizationUsageResult(int actualEmbeddingTokens, int actualChunkCount, String modelVersion) {
    }
}
