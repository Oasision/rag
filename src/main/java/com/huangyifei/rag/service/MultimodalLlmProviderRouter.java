package com.huangyifei.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huangyifei.rag.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class MultimodalLlmProviderRouter {

    private static final Logger logger = LoggerFactory.getLogger(MultimodalLlmProviderRouter.class);

    private final AiProperties aiProperties;
    private final RateLimitService rateLimitService;
    private final UsageQuotaService usageQuotaService;
    private final ModelProviderConfigService modelProviderConfigService;
    private final ObjectMapper objectMapper;

    public MultimodalLlmProviderRouter(AiProperties aiProperties,
                                       RateLimitService rateLimitService,
                                       UsageQuotaService usageQuotaService,
                                       ModelProviderConfigService modelProviderConfigService,
                                       ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.rateLimitService = rateLimitService;
        this.usageQuotaService = usageQuotaService;
        this.modelProviderConfigService = modelProviderConfigService;
        this.objectMapper = objectMapper;
    }

    public ChatStreamHandle streamResponse(String requesterId,
                                           String userMessage,
                                           String context,
                                           List<Map<String, String>> history,
                                           List<MultimodalAssetService.ImageAsset> imageAssets,
                                           Consumer<String> onChunk,
                                           Consumer<Throwable> onError) {
        ModelProviderConfigService.ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_MULTIMODAL_LLM);
        Map<String, Object> request = buildRequest(provider.model(), userMessage, context, history, imageAssets);

        int estimatedPromptTokens = buildEstimatedPromptTokens(userMessage, context, history, imageAssets);
        int maxCompletionTokens = aiProperties.getGeneration().getMaxTokens() != null
                ? aiProperties.getGeneration().getMaxTokens()
                : 2000;
        UsageQuotaService.TokenReservationBundle reservation = rateLimitService.reserveLlmUsage(
                requesterId, estimatedPromptTokens, maxCompletionTokens);
        StreamUsageTracker usageTracker = new StreamUsageTracker(reservation, estimatedPromptTokens);

        try {
            Disposable subscription = buildClient(provider)
                    .post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .subscribe(
                            chunk -> processChunk(chunk, usageTracker, onChunk),
                            error -> {
                                settleUsage(usageTracker);
                                onError.accept(error);
                            },
                            () -> settleUsage(usageTracker)
                    );
            return new ChatStreamHandle(subscription, () -> settleUsage(usageTracker));
        } catch (Exception exception) {
            usageQuotaService.abortReservation(reservation);
            throw exception;
        }
    }

    private WebClient buildClient(ModelProviderConfigService.ActiveProviderView provider) {
        WebClient.Builder builder = WebClient.builder().baseUrl(provider.apiBaseUrl());
        if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
        }
        return builder.build();
    }

    private Map<String, Object> buildRequest(String model,
                                             String userMessage,
                                             String context,
                                             List<Map<String, String>> history,
                                             List<MultimodalAssetService.ImageAsset> imageAssets) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history, imageAssets));
        request.put("stream", true);
        request.put("stream_options", Map.of("include_usage", true));

        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }

    private List<Map<String, Object>> buildMessages(String userMessage,
                                                    String context,
                                                    List<Map<String, String>> history,
                                                    List<MultimodalAssetService.ImageAsset> imageAssets) {
        List<Map<String, Object>> messages = new ArrayList<>();
        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        StringBuilder sysBuilder = new StringBuilder();
        if (promptCfg.getRules() != null) {
            sysBuilder.append(promptCfg.getRules()).append("\n\n");
        }
        sysBuilder.append("引用要求：参考资料以 [1]、[2] 这样的编号提供。回答中引用知识库内容时，不要只写 [1]，必须在相关句子末尾写成可读来源格式，例如：")
                .append("(来源#1: 文件名 (第1页))；如果没有页码则写成 (来源#1: 文件名)。不要编造不存在的来源编号。\n\n");
        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");
        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            sysBuilder.append(promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "锛堟湰杞棤妫€绱㈢粨鏋滐級").append("\n");
        }
        sysBuilder.append(refEnd);
        messages.add(Map.of("role", "system", "content", sysBuilder.toString()));

        if (history != null && !history.isEmpty()) {
            for (Map<String, String> item : history) {
                messages.add(Map.of(
                        "role", item.getOrDefault("role", "user"),
                        "content", item.getOrDefault("content", "")
                ));
            }
        }

        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", userMessage));
        if (imageAssets != null) {
            for (MultimodalAssetService.ImageAsset imageAsset : imageAssets) {
                userContent.add(Map.of(
                        "type", "image_url",
                        "image_url", Map.of("url", imageAsset.dataUrl())
                ));
            }
        }
        messages.add(Map.of("role", "user", "content", userContent));
        return messages;
    }

    private int buildEstimatedPromptTokens(String userMessage,
                                           String context,
                                           List<Map<String, String>> history,
                                           List<MultimodalAssetService.ImageAsset> imageAssets) {
        List<Map<String, String>> estimateMessages = new ArrayList<>();
        estimateMessages.add(Map.of("role", "system", "content", context == null ? "" : context));
        if (history != null) {
            estimateMessages.addAll(history);
        }
        StringBuilder finalUserPrompt = new StringBuilder(userMessage == null ? "" : userMessage);
        if (imageAssets != null && !imageAssets.isEmpty()) {
            finalUserPrompt.append("\n");
            for (MultimodalAssetService.ImageAsset imageAsset : imageAssets) {
                finalUserPrompt.append("[image:")
                        .append(imageAsset.fileName())
                        .append("]\n");
            }
        }
        estimateMessages.add(Map.of("role", "user", "content", finalUserPrompt.toString()));
        return usageQuotaService.estimateChatTokens(estimateMessages);
    }

    private void processChunk(String rawChunk, StreamUsageTracker usageTracker, Consumer<String> onChunk) {
        try {
            for (String chunk : extractPayloads(rawChunk)) {
                if ("[DONE]".equals(chunk)) {
                    continue;
                }

                JsonNode node = objectMapper.readTree(chunk);
                JsonNode usageNode = node.path("usage");
                if (usageNode.isObject()) {
                    usageTracker.promptTokens = usageNode.path("prompt_tokens").asInt(usageTracker.promptTokens);
                    usageTracker.completionTokens = usageNode.path("completion_tokens").asInt(usageTracker.completionTokens);
                }

                String content = node.path("choices")
                        .path(0)
                        .path("delta")
                        .path("content")
                        .asText("");
                if (!content.isEmpty()) {
                    usageTracker.responseContent.append(content);
                    onChunk.accept(content);
                }
            }
        } catch (Exception exception) {
            logger.error("Failed to process multimodal model stream chunk: {}", exception.getMessage(), exception);
        }
    }

    private List<String> extractPayloads(String rawChunk) {
        List<String> payloads = new ArrayList<>();
        if (rawChunk == null || rawChunk.isBlank()) {
            return payloads;
        }

        String trimmed = rawChunk.trim();
        for (String line : trimmed.split("\\r?\\n")) {
            String payload = line.trim();
            if (payload.isEmpty() || payload.startsWith(":")) {
                continue;
            }
            if (payload.startsWith("data:")) {
                payload = payload.substring(5).trim();
            }
            if (!payload.isEmpty()) {
                payloads.add(payload);
            }
        }

        if (payloads.isEmpty()) {
            payloads.add(trimmed);
        }
        return payloads;
    }

    private void settleUsage(StreamUsageTracker usageTracker) {
        if (usageTracker == null || usageTracker.settled) {
            return;
        }

        usageTracker.settled = true;
        int actualPromptTokens = usageTracker.promptTokens > 0
                ? usageTracker.promptTokens
                : usageTracker.estimatedPromptTokens;
        int actualCompletionTokens = usageTracker.completionTokens > 0
                ? usageTracker.completionTokens
                : usageQuotaService.estimateTextTokens(usageTracker.responseContent.toString());

        usageQuotaService.settleReservation(usageTracker.reservation, actualPromptTokens + actualCompletionTokens);
    }

    private static final class StreamUsageTracker {
        private final UsageQuotaService.TokenReservationBundle reservation;
        private final int estimatedPromptTokens;
        private final StringBuilder responseContent = new StringBuilder();
        private volatile int promptTokens;
        private volatile int completionTokens;
        private volatile boolean settled;

        private StreamUsageTracker(UsageQuotaService.TokenReservationBundle reservation, int estimatedPromptTokens) {
            this.reservation = reservation;
            this.estimatedPromptTokens = estimatedPromptTokens;
        }
    }
}
