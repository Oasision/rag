package com.huangyifei.rag.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huangyifei.rag.service.ModelProviderConfigService;
import com.huangyifei.rag.service.RateLimitService;
import com.huangyifei.rag.service.UsageQuotaService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class EmbeddingClient {

    public enum UsageType {
        UPLOAD,
        QUERY
    }

    @Value("${embedding.api.batch-size:100}")
    private int batchSize;

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingClient.class);
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final UsageQuotaService usageQuotaService;
    private final ModelProviderConfigService modelProviderConfigService;

    public EmbeddingClient(ObjectMapper objectMapper,
                           RateLimitService rateLimitService,
                           UsageQuotaService usageQuotaService,
                           ModelProviderConfigService modelProviderConfigService) {
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
        this.usageQuotaService = usageQuotaService;
        this.modelProviderConfigService = modelProviderConfigService;
    }

    @PostConstruct
    public void init() {
        ModelProviderConfigService.ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        logger.info("EmbeddingClient initialized - provider={}, model={}, batchSize={}, dimension={}, apiBaseUrl={}",
                provider.provider(), provider.model(), batchSize, provider.dimension(), provider.apiBaseUrl());
    }

    public List<float[]> embed(List<String> texts) {
        return embedWithUsage(texts, "system", UsageType.UPLOAD).vectors();
    }

    public List<float[]> embed(List<String> texts, String requesterId) {
        return embedWithUsage(texts, requesterId, UsageType.UPLOAD).vectors();
    }

    public List<float[]> embed(List<String> texts, String requesterId, UsageType usageType) {
        return embedWithUsage(texts, requesterId, usageType).vectors();
    }

    public EmbeddingUsageResult embedWithUsage(List<String> texts, String requesterId, UsageType usageType) {
        try {
            String normalizedRequesterId = requesterId == null || requesterId.isBlank() ? "unknown" : requesterId;
            logger.info("Embedding request started, textCount={}", texts.size());

            List<float[]> all = new ArrayList<>(texts.size());
            int totalTokens = 0;
            for (int start = 0; start < texts.size(); start += batchSize) {
                int end = Math.min(start + batchSize, texts.size());
                List<String> sub = texts.subList(start, end);
                UsageQuotaService.TokenReservationBundle reservation = usageType == UsageType.QUERY
                        ? rateLimitService.reserveEmbeddingQueryUsage(normalizedRequesterId, sub)
                        : rateLimitService.reserveEmbeddingUploadUsage(normalizedRequesterId, sub);
                logger.debug("Calling embedding API for range {}-{} (size={})", start, end - 1, sub.size());
                try {
                    String response = callApiOnce(sub);
                    EmbeddingApiResponse parsedResponse = parseEmbeddingResponse(response, sub);
                    usageQuotaService.settleReservation(reservation, parsedResponse.totalTokens());
                    all.addAll(parsedResponse.vectors());
                    totalTokens += parsedResponse.totalTokens();
                } catch (Exception e) {
                    usageQuotaService.abortReservation(reservation);
                    throw e;
                }
            }
            logger.info("Embedding request finished, vectorCount={}", all.size());
            return new EmbeddingUsageResult(all, totalTokens, currentModelVersion());
        } catch (WebClientResponseException e) {
            logger.error("Embedding API HTTP error - status={}, body={}, headers={}",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString(),
                    e.getHeaders());
            throw new RuntimeException(String.format(
                    "Embedding API failed: HTTP %d - %s",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString()), e);
        } catch (Exception e) {
            logger.error("Embedding API failed: {} - {}", e.getMessage(), e.getClass().getSimpleName(), e);
            throw new RuntimeException("Embedding API failed: " + e.getMessage(), e);
        }
    }

    private String callApiOnce(List<String> batch) {
        ModelProviderConfigService.ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", provider.model());
        requestBody.put("input", batch);
        if (provider.dimension() != null) {
            requestBody.put("dimension", provider.dimension());
        }
        requestBody.put("encoding_format", "float");

        logger.debug("Embedding request - provider={}, model={}, dimension={}, batchSize={}, firstText={}",
                provider.provider(), provider.model(), provider.dimension(), batch.size(),
                batch.isEmpty() ? "empty" : batch.get(0).substring(0, Math.min(50, batch.get(0).length())) + "...");

        return buildClient(provider).post()
                .uri("/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1))
                        .filter(e -> e instanceof WebClientResponseException)
                        .doBeforeRetry(signal -> logger.warn("Retry embedding API call - attempt={}, reason={}",
                                signal.totalRetries() + 1, signal.failure().getMessage())))
                .block(Duration.ofSeconds(30));
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

    private EmbeddingApiResponse parseEmbeddingResponse(String response, List<String> inputTexts) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(response);
        JsonNode data = jsonNode.get("data");
        if (data == null || !data.isArray()) {
            throw new RuntimeException("Embedding API response must contain a data array");
        }

        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (embedding != null && embedding.isArray()) {
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
        }

        JsonNode usage = jsonNode.path("usage");
        int totalTokens = usage.path("total_tokens").asInt(usage.path("input_tokens").asInt(0));
        return new EmbeddingApiResponse(
                vectors,
                totalTokens > 0 ? totalTokens : usageQuotaService.estimateEmbeddingTokens(inputTexts));
    }

    public String currentModelVersion() {
        ModelProviderConfigService.ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        return provider.provider() + ":" + provider.model() + ":" + provider.dimension();
    }

    private record EmbeddingApiResponse(List<float[]> vectors, int totalTokens) {
    }

    public record EmbeddingUsageResult(List<float[]> vectors, int totalTokens, String modelVersion) {
    }
}
