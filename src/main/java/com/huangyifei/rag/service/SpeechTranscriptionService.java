package com.huangyifei.rag.service;

import com.alibaba.nls.client.AccessToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huangyifei.rag.exception.CustomException;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class SpeechTranscriptionService {

    private static final Logger logger = LoggerFactory.getLogger(SpeechTranscriptionService.class);
    private static final long NLS_FLASH_MAX_BYTES = 100L * 1024L * 1024L;
    private static final long TOKEN_REFRESH_SKEW_SECONDS = 300L;

    private final ModelProviderConfigService modelProviderConfigService;
    private final ObjectMapper objectMapper;
    private final MinioClient minioClient;

    @Value("${speech.transcription.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${speech.transcription.audio.sample-rate:16000}")
    private int audioSampleRate;

    @Value("${speech.transcription.audio.bitrate:64k}")
    private String audioBitrate;

    @Value("${speech.transcription.audio.format:wav}")
    private String audioFormat;

    private volatile String cachedToken;
    private volatile long cachedTokenExpireTimeSeconds;
    private volatile String cachedTokenAccessKeyId;

    public SpeechTranscriptionService(
            ModelProviderConfigService modelProviderConfigService,
            ObjectMapper objectMapper,
            MinioClient minioClient
    ) {
        this.modelProviderConfigService = modelProviderConfigService;
        this.objectMapper = objectMapper;
        this.minioClient = minioClient;
    }

    public boolean isMediaFile(String fileName) {
        return switch (extensionOf(fileName)) {
            case "mp3", "wav", "m4a", "aac", "flac", "ogg", "wma",
                 "mp4", "mov", "avi", "mkv", "webm", "m4v", "wmv" -> true;
            default -> false;
        };
    }

    public TranscriptionResult transcribeFromStorage(String fileMd5, String fileName) {
        ModelProviderConfigService.ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_SPEECH_TRANSCRIPTION);
        validateProvider(provider);

        Path sourceFile = null;
        Path audioFile = null;
        try {
            String extension = extensionOf(fileName);
            sourceFile = downloadMergedObject(fileMd5, extension);
            audioFile = normalizeAudio(sourceFile, fileName);

            if (isLocalFunasrProvider(provider)) {
                TranscriptionResult result = submitLocalFunasr(provider, audioFile);
                logger.info("Local FunASR media transcription completed: fileMd5={}, fileName={}, segmentCount={}",
                        fileMd5, fileName, result.segments().size());
                return result;
            }

            long audioSize = Files.size(audioFile);
            if (audioSize > NLS_FLASH_MAX_BYTES) {
                throw new CustomException("音频文件超过 NLS 极速版 100MB 限制，当前大小: " + formatSize(audioSize), HttpStatus.BAD_REQUEST);
            }

            String token = getAccessToken(provider.apiKey(), provider.secondaryApiKey());
            JsonNode response = submitFlashRecognizer(provider, token, audioFile);
            List<TranscriptSegment> segments = parseSegments(response.toString());

            String text = joinSegmentText(segments);

            logger.info("NLS media transcription completed: fileMd5={}, fileName={}, segmentCount={}",
                    fileMd5, fileName, segments.size());
            return new TranscriptionResult("nls-flash", provider.model(), text, segments);
        } catch (CustomException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("NLS 语音转写失败: " + exception.getMessage(), exception);
        } finally {
            deleteTempFile(audioFile, sourceFile);
            deleteTempFile(sourceFile, null);
        }
    }

    private void validateProvider(ModelProviderConfigService.ActiveProviderView provider) {
        if (isLocalFunasrProvider(provider)) {
            if (provider.apiBaseUrl() == null || provider.apiBaseUrl().isBlank()) {
                throw new CustomException("Local FunASR API地址不能为空", HttpStatus.BAD_REQUEST);
            }
            if (provider.model() == null || provider.model().isBlank()) {
                throw new CustomException("Local FunASR模型不能为空", HttpStatus.BAD_REQUEST);
            }
            return;
        }
        if (provider.apiKey() == null || provider.apiKey().isBlank()) {
            throw new CustomException("Speech transcription AccessKeyId不能为空", HttpStatus.BAD_REQUEST);
        }
        if (provider.secondaryApiKey() == null || provider.secondaryApiKey().isBlank()) {
            throw new CustomException("Speech transcription AccessKeySecret不能为空", HttpStatus.BAD_REQUEST);
        }
        if (provider.model() == null || provider.model().isBlank()) {
            throw new CustomException("Speech transcription AppKey不能为空", HttpStatus.BAD_REQUEST);
        }
    }

    private Path downloadMergedObject(String fileMd5, String extension) throws Exception {
        Path tempFile = Files.createTempFile("rag-media-", "." + (extension.isBlank() ? "bin" : extension));
        try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket("uploads")
                .object("merged/" + fileMd5)
                .build())) {
            Files.copy(inputStream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }

    private Path normalizeAudio(Path sourceFile, String fileName) throws Exception {
        String normalizedFormat = normalizedAudioFormat();
        Path audioFile = Files.createTempFile("rag-audio-", "." + normalizedFormat);
        List<String> command = new ArrayList<>(List.of(
                ffmpegPath,
                "-y",
                "-i", sourceFile.toAbsolutePath().toString(),
                "-vn",
                "-ac", "1",
                "-ar", String.valueOf(audioSampleRate)
        ));

        if ("wav".equals(normalizedFormat)) {
            command.addAll(List.of("-acodec", "pcm_s16le", "-f", "wav"));
        } else {
            command.addAll(List.of("-b:a", audioBitrate, "-f", normalizedFormat));
        }
        command.add(audioFile.toAbsolutePath().toString());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("FFmpeg 音频规范化超时: " + fileName);
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("FFmpeg 音频规范化失败，请检查 speech.transcription.ffmpeg.path: " + output);
        }
        return audioFile;
    }

    private synchronized String getAccessToken(String accessKeyId, String accessKeySecret) throws Exception {
        long nowSeconds = System.currentTimeMillis() / 1000L;
        if (cachedToken != null
                && cachedTokenExpireTimeSeconds - TOKEN_REFRESH_SKEW_SECONDS > nowSeconds
                && accessKeyId.equals(cachedTokenAccessKeyId)) {
            return cachedToken;
        }

        AccessToken accessToken = new AccessToken(accessKeyId.trim(), accessKeySecret.trim());
        accessToken.apply();
        cachedToken = accessToken.getToken();
        cachedTokenExpireTimeSeconds = accessToken.getExpireTime();
        cachedTokenAccessKeyId = accessKeyId;
        return cachedToken;
    }

    private JsonNode submitFlashRecognizer(
            ModelProviderConfigService.ActiveProviderView provider,
            String token,
            Path audioFile
    ) throws Exception {
        URI uri = UriComponentsBuilder
                .fromHttpUrl(provider.apiBaseUrl())
                .queryParam("appkey", provider.model())
                .queryParam("token", token)
                .queryParam("format", normalizedAudioFormat())
                .queryParam("sample_rate", audioSampleRate)
                .queryParam("enable_word_level_result", false)
                .queryParam("enable_inverse_text_normalization", true)
                .build()
                .toUri();

        String response;
        try {
            response = WebClient.builder()
                    .build()
                    .post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .bodyValue(Files.readAllBytes(audioFile))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMinutes(3));
        } catch (WebClientResponseException exception) {
            throw new RuntimeException(
                    "NLS FlashRecognizer HTTP " + exception.getRawStatusCode() + ": " + exception.getResponseBodyAsString(),
                    exception);
        }

        JsonNode root = objectMapper.readTree(response);
        int status = root.path("status").asInt(200);
        if (status != 20000000 && status != 200) {
            throw new RuntimeException("NLS FlashRecognizer returned error: " + response);
        }
        return root;
    }

    private TranscriptionResult submitLocalFunasr(ModelProviderConfigService.ActiveProviderView provider, Path audioFile) throws Exception {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new FileSystemResource(audioFile));

        String response;
        try {
            response = WebClient.builder()
                    .build()
                    .post()
                    .uri(normalizeLocalFunasrTranscribeUrl(provider.apiBaseUrl()))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(parts)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMinutes(30));
        } catch (WebClientResponseException exception) {
            throw new RuntimeException(
                    "Local FunASR HTTP " + exception.getRawStatusCode() + ": " + exception.getResponseBodyAsString(),
                    exception);
        }

        JsonNode root = objectMapper.readTree(response);
        List<TranscriptSegment> segments = parseLocalFunasrSegments(root);
        String text = joinSegmentText(segments);
        if (text.isBlank()) {
            text = root.path("text").asText("");
        }
        return new TranscriptionResult("funasr-local", provider.model(), text, segments);
    }

    private String normalizeLocalFunasrTranscribeUrl(String apiBaseUrl) {
        String value = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        if (value.endsWith("/transcribe")) {
            return value;
        }
        return (value.endsWith("/") ? value.substring(0, value.length() - 1) : value) + "/transcribe";
    }

    private boolean isLocalFunasrProvider(ModelProviderConfigService.ActiveProviderView provider) {
        return provider != null && ModelProviderConfigService.API_STYLE_LOCAL_FUNASR.equals(provider.apiStyle());
    }

    private String normalizedAudioFormat() {
        String value = audioFormat == null ? "wav" : audioFormat.trim().toLowerCase(Locale.ROOT);
        if (!List.of("wav", "mp3").contains(value)) {
            return "wav";
        }
        return value;
    }

    private List<TranscriptSegment> parseSegments(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<TranscriptSegment> segments = new ArrayList<>();
            collectSegments(root, segments);
            if (segments.isEmpty()) {
                collectTextFallback(root, segments);
            }
            return segments;
        } catch (Exception exception) {
            throw new RuntimeException("Failed to parse media transcription result", exception);
        }
    }

    private List<TranscriptSegment> parseLocalFunasrSegments(JsonNode root) {
        List<TranscriptSegment> segments = new ArrayList<>();
        JsonNode segmentNodes = root.path("segments");
        if (segmentNodes.isArray()) {
            for (JsonNode item : segmentNodes) {
                JsonNode text = firstTextNode(item, "text", "sentence", "transcript");
                if (text == null || text.asText("").isBlank()) {
                    continue;
                }
                segments.add(new TranscriptSegment(
                        item.path("startTimeMs").asLong(item.path("begin_time").asLong(item.path("start_time").asLong(-1))),
                        item.path("endTimeMs").asLong(item.path("end_time").asLong(-1)),
                        text.asText()));
            }
        }
        if (segments.isEmpty()) {
            JsonNode text = firstTextNode(root, "text", "transcript", "result");
            if (text != null && !text.asText("").isBlank()) {
                segments.add(new TranscriptSegment(-1, -1, text.asText()));
            }
        }
        return segments;
    }

    private void collectSegments(JsonNode node, List<TranscriptSegment> segments) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode text = firstTextNode(node, "text", "sentence", "transcript");
            if (text != null && !text.asText("").isBlank()) {
                segments.add(new TranscriptSegment(
                        node.path("begin_time").asLong(node.path("start_time").asLong(node.path("startTimeMs").asLong(-1))),
                        node.path("end_time").asLong(node.path("endTimeMs").asLong(-1)),
                        text.asText()));
            }
            node.fields().forEachRemaining(entry -> collectSegments(entry.getValue(), segments));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectSegments(item, segments);
            }
        }
    }

    private void collectTextFallback(JsonNode node, List<TranscriptSegment> segments) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode text = firstTextNode(node, "text", "transcript", "result");
            if (text != null && !text.asText("").isBlank()) {
                segments.add(new TranscriptSegment(-1, -1, text.asText()));
                return;
            }
            node.fields().forEachRemaining(entry -> collectTextFallback(entry.getValue(), segments));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectTextFallback(item, segments);
            }
        }
    }

    private JsonNode firstTextNode(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isTextual() && !value.asText("").isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String joinSegmentText(List<TranscriptSegment> segments) {
        StringBuilder text = new StringBuilder();
        for (TranscriptSegment segment : segments) {
            if (segment.text() == null || segment.text().isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(segment.text().trim());
        }
        return text.toString();
    }

    private String extensionOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.2fMB", bytes / 1024.0 / 1024.0);
    }

    private void deleteTempFile(Path path, Path skip) {
        if (path == null || path.equals(skip)) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception exception) {
            logger.warn("Failed to delete temp media file: {}", path, exception);
        }
    }

    public record TranscriptionResult(
            String taskId,
            String modelVersion,
            String text,
            List<TranscriptSegment> segments
    ) {
    }

    public record TranscriptSegment(
            long startTimeMs,
            long endTimeMs,
            String text
    ) {
    }
}
