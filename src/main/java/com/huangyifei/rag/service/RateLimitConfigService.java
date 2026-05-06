package com.huangyifei.rag.service;

import com.huangyifei.rag.config.RateLimitProperties;
import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.model.RateLimitConfig;
import com.huangyifei.rag.repository.RateLimitConfigRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RateLimitConfigService {

    private static final String CHAT_MESSAGE = "chat-message";
    private static final String LLM_GLOBAL_TOKEN = "llm-global-token";
    private static final String EMBEDDING_UPLOAD_TOKEN = "embedding-upload-token";
    private static final String EMBEDDING_QUERY_REQUEST = "embedding-query-request";
    private static final String EMBEDDING_QUERY_GLOBAL_TOKEN = "embedding-query-global-token";

    private final RateLimitProperties properties;
    private final RateLimitConfigRepository repository;
    private volatile RateLimitSettingsView currentSettings;

    public RateLimitConfigService(RateLimitProperties properties, RateLimitConfigRepository repository) {
        this.properties = properties;
        this.repository = repository;
        this.currentSettings = buildDefaultSettings();
    }

    @PostConstruct
    public void loadPersistedConfigs() {
        currentSettings = mergeOverrides(buildDefaultSettings(), repository.findAll());
    }

    public RateLimitSettingsView getCurrentSettings() {
        return currentSettings;
    }

    public synchronized RateLimitSettingsView updateSettings(UpdateRateLimitRequest request, String updatedBy) {
        if (request == null) {
            throw new CustomException("限流配置不能为空", HttpStatus.BAD_REQUEST);
        }
        validateWindowLimit(request.chatMessage(), "聊天消息");
        validateTokenBudgetLimit(request.llmGlobalToken(), "LLM 全局 Token");
        validateTokenBudgetLimit(request.embeddingUploadToken(), "Embedding 上传 Token");
        validateDualWindowLimit(request.embeddingQueryRequest(), "Embedding 查询请求");
        validateTokenBudgetLimit(request.embeddingQueryGlobalToken(), "Embedding 查询全局 Token");

        persistWindowLimit(CHAT_MESSAGE, request.chatMessage(), updatedBy);
        persistTokenBudgetLimit(LLM_GLOBAL_TOKEN, request.llmGlobalToken(), updatedBy);
        persistTokenBudgetLimit(EMBEDDING_UPLOAD_TOKEN, request.embeddingUploadToken(), updatedBy);
        persistDualWindowLimit(EMBEDDING_QUERY_REQUEST, request.embeddingQueryRequest(), updatedBy);
        persistTokenBudgetLimit(EMBEDDING_QUERY_GLOBAL_TOKEN, request.embeddingQueryGlobalToken(), updatedBy);

        currentSettings = new RateLimitSettingsView(
                request.chatMessage(),
                request.llmGlobalToken(),
                request.embeddingUploadToken(),
                request.embeddingQueryRequest(),
                request.embeddingQueryGlobalToken());
        return currentSettings;
    }

    private RateLimitSettingsView buildDefaultSettings() {
        return new RateLimitSettingsView(
                new WindowLimitView(properties.getChatMessage().getMax(), properties.getChatMessage().getWindowSeconds()),
                new TokenBudgetView(
                        properties.getLlmGlobalToken().getMinuteMax(),
                        properties.getLlmGlobalToken().getMinuteWindowSeconds(),
                        properties.getLlmGlobalToken().getDayMax(),
                        properties.getLlmGlobalToken().getDayWindowSeconds()),
                new TokenBudgetView(
                        properties.getEmbeddingUploadToken().getMinuteMax(),
                        properties.getEmbeddingUploadToken().getMinuteWindowSeconds(),
                        properties.getEmbeddingUploadToken().getDayMax(),
                        properties.getEmbeddingUploadToken().getDayWindowSeconds()),
                new DualWindowLimitView(
                        properties.getEmbeddingQueryRequest().getMinuteMax(),
                        properties.getEmbeddingQueryRequest().getMinuteWindowSeconds(),
                        properties.getEmbeddingQueryRequest().getDayMax(),
                        properties.getEmbeddingQueryRequest().getDayWindowSeconds()),
                new TokenBudgetView(
                        properties.getEmbeddingQueryGlobalToken().getMinuteMax(),
                        properties.getEmbeddingQueryGlobalToken().getMinuteWindowSeconds(),
                        properties.getEmbeddingQueryGlobalToken().getDayMax(),
                        properties.getEmbeddingQueryGlobalToken().getDayWindowSeconds()));
    }

    private RateLimitSettingsView mergeOverrides(RateLimitSettingsView defaults, List<RateLimitConfig> configs) {
        WindowLimitView chatMessage = defaults.chatMessage();
        TokenBudgetView llmGlobalToken = defaults.llmGlobalToken();
        TokenBudgetView embeddingUploadToken = defaults.embeddingUploadToken();
        DualWindowLimitView embeddingQueryRequest = defaults.embeddingQueryRequest();
        TokenBudgetView embeddingQueryGlobalToken = defaults.embeddingQueryGlobalToken();

        for (RateLimitConfig config : configs) {
            if (config == null || config.getConfigKey() == null) {
                continue;
            }
            switch (config.getConfigKey()) {
                case CHAT_MESSAGE -> chatMessage = new WindowLimitView(config.getSingleMax(), config.getSingleWindowSeconds());
                case LLM_GLOBAL_TOKEN -> llmGlobalToken = toTokenBudget(config, llmGlobalToken);
                case EMBEDDING_UPLOAD_TOKEN -> embeddingUploadToken = toTokenBudget(config, embeddingUploadToken);
                case EMBEDDING_QUERY_REQUEST -> embeddingQueryRequest = new DualWindowLimitView(
                        config.getMinuteMax(), config.getMinuteWindowSeconds(), config.getDayMax(), config.getDayWindowSeconds());
                case EMBEDDING_QUERY_GLOBAL_TOKEN -> embeddingQueryGlobalToken = toTokenBudget(config, embeddingQueryGlobalToken);
                default -> {
                }
            }
        }
        return new RateLimitSettingsView(chatMessage, llmGlobalToken, embeddingUploadToken, embeddingQueryRequest, embeddingQueryGlobalToken);
    }

    private TokenBudgetView toTokenBudget(RateLimitConfig config, TokenBudgetView fallback) {
        if (config.getMinuteMax() == null || config.getMinuteWindowSeconds() == null
                || config.getDayMax() == null || config.getDayWindowSeconds() == null) {
            return fallback;
        }
        return new TokenBudgetView(
                config.getMinuteMax(),
                config.getMinuteWindowSeconds(),
                config.getDayMax(),
                config.getDayWindowSeconds());
    }

    private void persistWindowLimit(String key, WindowLimitView limit, String updatedBy) {
        RateLimitConfig config = repository.findById(key).orElseGet(RateLimitConfig::new);
        config.setConfigKey(key);
        config.setSingleMax(limit.max());
        config.setSingleWindowSeconds(limit.windowSeconds());
        config.setMinuteMax(null);
        config.setMinuteWindowSeconds(null);
        config.setDayMax(null);
        config.setDayWindowSeconds(null);
        config.setUpdatedBy(updatedBy);
        repository.save(config);
    }

    private void persistDualWindowLimit(String key, DualWindowLimitView limit, String updatedBy) {
        RateLimitConfig config = repository.findById(key).orElseGet(RateLimitConfig::new);
        config.setConfigKey(key);
        config.setSingleMax(null);
        config.setSingleWindowSeconds(null);
        config.setMinuteMax(limit.minuteMax());
        config.setMinuteWindowSeconds(limit.minuteWindowSeconds());
        config.setDayMax(limit.dayMax());
        config.setDayWindowSeconds(limit.dayWindowSeconds());
        config.setUpdatedBy(updatedBy);
        repository.save(config);
    }

    private void persistTokenBudgetLimit(String key, TokenBudgetView limit, String updatedBy) {
        RateLimitConfig config = repository.findById(key).orElseGet(RateLimitConfig::new);
        config.setConfigKey(key);
        config.setSingleMax(null);
        config.setSingleWindowSeconds(null);
        config.setMinuteMax(limit.minuteMax());
        config.setMinuteWindowSeconds(limit.minuteWindowSeconds());
        config.setDayMax(limit.dayMax());
        config.setDayWindowSeconds(limit.dayWindowSeconds());
        config.setUpdatedBy(updatedBy);
        repository.save(config);
    }

    private void validateWindowLimit(WindowLimitView limit, String label) {
        if (limit == null || limit.max() <= 0 || limit.windowSeconds() <= 0) {
            throw new CustomException(label + "限流配置必须大于 0", HttpStatus.BAD_REQUEST);
        }
    }

    private void validateDualWindowLimit(DualWindowLimitView limit, String label) {
        if (limit == null || limit.minuteMax() <= 0 || limit.minuteWindowSeconds() <= 0
                || limit.dayMax() <= 0 || limit.dayWindowSeconds() <= 0) {
            throw new CustomException(label + "限流配置必须大于 0", HttpStatus.BAD_REQUEST);
        }
        if (limit.dayMax() < limit.minuteMax() || limit.dayWindowSeconds() < limit.minuteWindowSeconds()) {
            throw new CustomException(label + "日限额不能小于分钟限额", HttpStatus.BAD_REQUEST);
        }
    }

    private void validateTokenBudgetLimit(TokenBudgetView limit, String label) {
        if (limit == null || limit.minuteMax() <= 0 || limit.minuteWindowSeconds() <= 0
                || limit.dayMax() <= 0 || limit.dayWindowSeconds() <= 0) {
            throw new CustomException(label + "限流配置必须大于 0", HttpStatus.BAD_REQUEST);
        }
        if (limit.dayMax() < limit.minuteMax() || limit.dayWindowSeconds() < limit.minuteWindowSeconds()) {
            throw new CustomException(label + "日限额不能小于分钟限额", HttpStatus.BAD_REQUEST);
        }
    }

    public record WindowLimitView(int max, long windowSeconds) {
    }

    public record DualWindowLimitView(long minuteMax, long minuteWindowSeconds, long dayMax, long dayWindowSeconds) {
    }

    public record TokenBudgetView(long minuteMax, long minuteWindowSeconds, long dayMax, long dayWindowSeconds) {
    }

    public record RateLimitSettingsView(
            WindowLimitView chatMessage,
            TokenBudgetView llmGlobalToken,
            TokenBudgetView embeddingUploadToken,
            DualWindowLimitView embeddingQueryRequest,
            TokenBudgetView embeddingQueryGlobalToken
    ) {
    }

    public record UpdateRateLimitRequest(
            WindowLimitView chatMessage,
            TokenBudgetView llmGlobalToken,
            TokenBudgetView embeddingUploadToken,
            DualWindowLimitView embeddingQueryRequest,
            TokenBudgetView embeddingQueryGlobalToken
    ) {
    }
}
