package com.huangyifei.rag.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huangyifei.rag.service.ModelProviderConfigService;
import com.huangyifei.rag.service.RateLimitService;
import com.huangyifei.rag.service.UsageQuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class VisionEmbeddingClient {

    private static final Logger logger = LoggerFactory.getLogger(VisionEmbeddingClient.class);
    private static final String EMBEDDING_PATH = "/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";

    public enum UsageType {
        UPLOAD,
        QUERY
    }

    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final UsageQuotaService usageQuotaService;
    private final ModelProviderConfigService modelProviderConfigService;

    public VisionEmbeddingClient(ObjectMapper objectMapper,
                                 RateLimitService rateLimitService,
                                 UsageQuotaService usageQuotaService,
                                 ModelProviderConfigService modelProviderConfigService) {
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
        this.usageQuotaService = usageQuotaService;
        this.modelProviderConfigService = modelProviderConfigService;
    }

    public EmbeddingUsageResult embedText(String text, String requesterId, UsageType usageType) {
        return embedContents(
                List.of(Map.of("text", text)),
                List.of(text == null ? "" : text),
                requesterId,
                usageType
        );
    }

    public EmbeddingUsageResult embedImage(byte[] imageBytes, String mimeType, String requesterId, UsageType usageType) {
        String dataUrl = toDataUrl(imageBytes, mimeType);
        return embedContents(
                List.of(Map.of("image", dataUrl)),
                List.of("[image]"),
                requesterId,
                usageType
        );
    }

    public String currentModelVersion() {
        ModelProviderConfigService.ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_VISION_EMBEDDING);
        return provider.provider() + ":" + provider.model() + ":" + provider.dimension();
    }

    private EmbeddingUsageResult embedContents(List<Map<String, Object>> contents,
                                               List<String> usageSamples,
                                               String requesterId,
                                               UsageType usageType) {
        ModelProviderConfigService.ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_VISION_EMBEDDING);

        UsageQuotaService.TokenReservationBundle reservation = usageType == UsageType.QUERY
                ? rateLimitService.reserveEmbeddingQueryUsage(normalizeRequesterId(requesterId), usageSamples)
                : rateLimitService.reserveEmbeddingUploadUsage(normalizeRequesterId(requesterId), usageSamples);

        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", provider.model());
            requestBody.put("input", Map.of("contents", contents));

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("output_type", "dense");
            if (provider.dimension() != null) {
                parameters.put("dimension", provider.dimension());
            }
            requestBody.put("parameters", parameters);

            String response = buildClient(provider)
                    .post()
                    .uri(EMBEDDING_PATH)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.fixedDelay(2, Duration.ofSeconds(1)))
                    .block(Duration.ofSeconds(30));

            EmbeddingUsageResult parsed = parseEmbeddingResponse(response, usageSamples);
            usageQuotaService.settleReservation(reservation, parsed.totalTokens());
            return parsed;
        } catch (Exception exception) {
            usageQuotaService.abortReservation(reservation);
            throw new RuntimeException("Vision embedding generation failed: " + exception.getMessage(), exception);
        }
    }

    private WebClient buildClient(ModelProviderConfigService.ActiveProviderView provider) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(provider.apiBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));
        if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
        }
        return builder.build();
    }

    private EmbeddingUsageResult parseEmbeddingResponse(String response, List<String> usageSamples) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        JsonNode embeddingsNode = root.path("output").path("embeddings");
        if (!embeddingsNode.isArray() || embeddingsNode.isEmpty()) {
            embeddingsNode = root.path("data");
        }
        if (!embeddingsNode.isArray() || embeddingsNode.isEmpty()) {
            throw new IllegalStateException("Vision embedding API response does not contain embeddings");
        }

        List<float[]> vectors = new ArrayList<>(embeddingsNode.size());
        for (JsonNode item : embeddingsNode) {
            JsonNode embeddingNode = item.path("embedding");
            if (!embeddingNode.isArray() || embeddingNode.isEmpty()) {
                continue;
            }
            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }
            vectors.add(vector);
        }

        JsonNode usage = root.path("usage");
        int totalTokens = usage.path("total_tokens").asInt(usage.path("input_tokens").asInt(0));
        if (totalTokens <= 0) {
            totalTokens = Math.max(usageQuotaService.estimateEmbeddingTokens(usageSamples), 1);
        }

        return new EmbeddingUsageResult(vectors, totalTokens, currentModelVersion());
    }

    private String normalizeRequesterId(String requesterId) {
        return requesterId == null || requesterId.isBlank() ? "unknown" : requesterId;
    }

    private String toDataUrl(byte[] bytes, String mimeType) {
        String normalizedMimeType = mimeType == null || mimeType.isBlank() ? "image/png" : mimeType;
        return "data:" + normalizedMimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    public record EmbeddingUsageResult(List<float[]> vectors, int totalTokens, String modelVersion) {
    }
}
