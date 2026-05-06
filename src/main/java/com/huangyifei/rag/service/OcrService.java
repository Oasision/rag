package com.huangyifei.rag.service;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeGeneralRequest;
import com.aliyun.ocr_api20210707.models.RecognizeGeneralResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Service
public class OcrService {

    private static final Logger logger = LoggerFactory.getLogger(OcrService.class);
    private static final Set<String> OCR_IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "bmp", "gif", "tif", "tiff", "webp"
    ));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelProviderConfigService modelProviderConfigService;

    @Value("${file.ocr.enabled:false}")
    private boolean enabled;

    @Value("${file.ocr.aliyun.connect-timeout-millis:10000}")
    private Integer connectTimeoutMillis;

    @Value("${file.ocr.aliyun.read-timeout-millis:60000}")
    private Integer readTimeoutMillis;

    public OcrService(ModelProviderConfigService modelProviderConfigService) {
        this.modelProviderConfigService = modelProviderConfigService;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean canProcessImageFiles() {
        return enabled && hasCredentials(resolveProviderSettings());
    }

    public boolean isImageFile(String fileName) {
        String extension = extractExtension(fileName);
        return extension != null && OCR_IMAGE_EXTENSIONS.contains(extension);
    }

    public Set<String> getSupportedImageExtensions() {
        return new HashSet<>(OCR_IMAGE_EXTENSIONS);
    }

    public String extractTextFromImage(InputStream imageStream, String sourceName) throws IOException, TikaException {
        OcrProviderSettings providerSettings = resolveProviderSettings();
        ensureOcrReady("Image OCR", providerSettings);

        try {
            RecognizeGeneralRequest request = new RecognizeGeneralRequest();
            request.setBody(imageStream);

            RuntimeOptions runtimeOptions = buildRuntimeOptions();
            RecognizeGeneralResponse response = createClient(providerSettings).recognizeGeneralWithOptions(request, runtimeOptions);
            String text = extractTextFromResponse(response);
            logger.info("Alibaba OCR completed: sourceName={}, textLength={}", sourceName, text.length());
            return text;
        } catch (Exception e) {
            logger.error("Alibaba OCR failed: sourceName={}", sourceName, e);
            throw new TikaException("Alibaba OCR failed: " + e.getMessage(), e);
        }
    }

    public String extractTextFromImage(BufferedImage image, String sourceName) throws IOException, TikaException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            try (InputStream imageStream = new ByteArrayInputStream(outputStream.toByteArray())) {
                return extractTextFromImage(imageStream, sourceName);
            }
        }
    }

    private void ensureOcrReady(String scenario, OcrProviderSettings providerSettings) {
        if (!enabled) {
            throw new IllegalStateException(scenario + " is disabled. Please enable file.ocr.enabled first.");
        }

        if (!hasCredentials(providerSettings)) {
            throw new IllegalStateException(scenario + " is unavailable. Please configure OCR AccessKeyId, AccessKeySecret and Endpoint on the model provider page first.");
        }
    }

    private boolean hasCredentials(OcrProviderSettings providerSettings) {
        return providerSettings.endpoint() != null && !providerSettings.endpoint().isBlank()
                && providerSettings.accessKeyId() != null && !providerSettings.accessKeyId().isBlank()
                && providerSettings.accessKeySecret() != null && !providerSettings.accessKeySecret().isBlank();
    }

    private Client createClient(OcrProviderSettings providerSettings) throws Exception {
        Config config = new Config();
        config.setAccessKeyId(providerSettings.accessKeyId().trim());
        config.setAccessKeySecret(providerSettings.accessKeySecret().trim());
        config.setEndpoint(providerSettings.endpoint().trim());
        return new Client(config);
    }

    private OcrProviderSettings resolveProviderSettings() {
        try {
            ModelProviderConfigService.ActiveProviderView provider =
                    modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_OCR);
            return new OcrProviderSettings(
                    normalizeEndpoint(provider.apiBaseUrl()),
                    provider.apiKey(),
                    provider.secondaryApiKey()
            );
        } catch (Exception exception) {
            logger.warn("OCR provider settings are not ready: {}", exception.getMessage());
            return new OcrProviderSettings(null, null, null);
        }
    }

    private String normalizeEndpoint(String rawEndpoint) {
        if (rawEndpoint == null || rawEndpoint.isBlank()) {
            return null;
        }

        String normalized = rawEndpoint.trim().replaceFirst("^https?://", "");
        int slashIndex = normalized.indexOf('/');
        if (slashIndex >= 0) {
            normalized = normalized.substring(0, slashIndex);
        }
        return normalized;
    }

    private RuntimeOptions buildRuntimeOptions() {
        RuntimeOptions runtimeOptions = new RuntimeOptions();
        runtimeOptions.setConnectTimeout(connectTimeoutMillis);
        runtimeOptions.setReadTimeout(readTimeoutMillis);
        return runtimeOptions;
    }

    private String extractTextFromResponse(RecognizeGeneralResponse response) throws IOException {
        if (response == null || response.getBody() == null || response.getBody().getData() == null) {
            return "";
        }

        String data = response.getBody().getData();
        if (data.isBlank()) {
            return "";
        }

        JsonNode root = objectMapper.readTree(data);
        StringBuilder text = new StringBuilder();

        appendTextIfPresent(root, "content", text);
        appendTextIfPresent(root, "text", text);

        if (text.length() == 0) {
            appendWords(root.path("prism_wordsInfo"), "word", text);
            appendWords(root.path("wordsInfo"), "word", text);
            appendWords(root.path("words"), null, text);
        }

        return normalizeOcrText(text.toString());
    }

    private void appendTextIfPresent(JsonNode root, String fieldName, StringBuilder text) {
        JsonNode fieldNode = root.path(fieldName);
        if (fieldNode.isTextual()) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(fieldNode.asText());
        }
    }

    private void appendWords(JsonNode wordsNode, String wordField, StringBuilder text) {
        if (!wordsNode.isArray()) {
            return;
        }

        for (JsonNode item : wordsNode) {
            String value;
            if (wordField == null && item.isTextual()) {
                value = item.asText();
            } else {
                value = item.path(wordField).asText("");
            }

            if (value == null || value.isBlank()) {
                continue;
            }

            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(value.trim());
        }
    }

    private String extractExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }

        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex < 0 || lastDotIndex == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(lastDotIndex + 1).toLowerCase();
    }

    private String normalizeOcrText(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        return rawText
                .replace("\r", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private record OcrProviderSettings(
            String endpoint,
            String accessKeyId,
            String accessKeySecret
    ) {
    }
}
