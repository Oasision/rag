package com.huangyifei.rag.service;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeGeneralRequest;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.model.ModelProviderConfig;
import com.huangyifei.rag.repository.ModelProviderConfigRepository;
import com.huangyifei.rag.utils.SecretCryptoService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class ModelProviderConfigService {

    public static final String SCOPE_LLM = "llm";
    public static final String SCOPE_EMBEDDING = "embedding";
    public static final String SCOPE_OCR = "ocr";
    public static final String SCOPE_VISION_EMBEDDING = "vision_embedding";
    public static final String SCOPE_MULTIMODAL_LLM = "multimodal_llm";
    public static final String SCOPE_SPEECH_TRANSCRIPTION = "speech_transcription";

    public static final String API_STYLE_OPENAI = "openai-compatible";
    public static final String API_STYLE_ALIYUN_OCR = "aliyun-ocr";
    public static final String API_STYLE_ALIYUN_DASHSCOPE = "aliyun-dashscope";
    public static final String API_STYLE_ALIYUN_NLS_FLASH_ASR = "aliyun-nls-flash-asr";
    public static final String API_STYLE_LOCAL_FUNASR = "local-funasr";

    private static final String OCR_PROVIDER_ALIYUN = "aliyun";
    private static final String OCR_MODEL_RECOGNIZE_GENERAL = "RecognizeGeneral";
    private static final byte[] OCR_TEST_IMAGE_BYTES = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAoAAAAKCAYAAACNMs+9AAAAFElEQVR42mP8z8Dwn4EIwESJ5lEDACt2AhLqB8cYAAAAAElFTkSuQmCC"
    );

    private final ModelProviderConfigRepository repository;
    private final SecretCryptoService secretCryptoService;
    private volatile ModelProviderSettingsView currentSettings;

    @Value("${deepseek.api.url:https://api.deepseek.com/v1}")
    private String deepSeekApiUrl;

    @Value("${deepseek.api.key:}")
    private String deepSeekApiKey;

    @Value("${deepseek.api.model:deepseek-chat}")
    private String deepSeekModel;

    @Value("${embedding.api.url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String embeddingApiUrl;

    @Value("${embedding.api.key:}")
    private String embeddingApiKey;

    @Value("${embedding.api.model:text-embedding-v4}")
    private String embeddingModel;

    @Value("${embedding.api.dimension:2048}")
    private Integer embeddingDimension;

    @Value("${file.ocr.aliyun.endpoint:ocr-api.cn-hangzhou.aliyuncs.com}")
    private String ocrEndpoint;

    @Value("${file.ocr.aliyun.access-key-id:}")
    private String ocrAccessKeyId;

    @Value("${file.ocr.aliyun.access-key-secret:}")
    private String ocrAccessKeySecret;

    @Value("${vision.embedding.api.url:https://dashscope.aliyuncs.com}")
    private String visionEmbeddingApiUrl;

    @Value("${vision.embedding.api.key:}")
    private String visionEmbeddingApiKey;

    @Value("${vision.embedding.api.model:multimodal-embedding-v1}")
    private String visionEmbeddingModel;

    @Value("${vision.embedding.api.dimension:1024}")
    private Integer visionEmbeddingDimension;

    @Value("${multimodal.llm.api.url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String multimodalLlmApiUrl;

    @Value("${multimodal.llm.api.key:}")
    private String multimodalLlmApiKey;

    @Value("${multimodal.llm.api.model:qwen-vl-plus}")
    private String multimodalLlmModel;

    @Value("${speech.transcription.api.url:https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/FlashRecognizer}")
    private String speechTranscriptionApiUrl;

    @Value("${speech.transcription.api.access-key-id:${speech.transcription.api.key:}}")
    private String speechTranscriptionAccessKeyId;

    @Value("${speech.transcription.api.access-key-secret:}")
    private String speechTranscriptionAccessKeySecret;

    @Value("${speech.transcription.api.app-key:${speech.transcription.api.model:}}")
    private String speechTranscriptionAppKey;

    @Value("${speech.transcription.local.url:http://127.0.0.1:9880}")
    private String localFunasrApiUrl;

    @Value("${speech.transcription.local.model:paraformer-zh}")
    private String localFunasrModel;

    public ModelProviderConfigService(ModelProviderConfigRepository repository, SecretCryptoService secretCryptoService) {
        this.repository = repository;
        this.secretCryptoService = secretCryptoService;
        this.currentSettings = buildDefaultSettings();
    }

    @PostConstruct
    public void loadPersistedConfigs() {
        reloadSettings();
    }

    public ModelProviderSettingsView getCurrentSettings() {
        return currentSettings;
    }

    public ActiveProviderView getActiveProvider(String scope) {
        String normalizedScope = normalizeScope(scope);
        ScopeSettingsView settings = resolveScope(normalizedScope, currentSettings);
        return settings.providers().stream()
                .filter(ProviderConfigView::active)
                .findFirst()
                .map(provider -> toActiveProvider(normalizedScope, provider))
                .orElseThrow(() -> new CustomException("未配置可用 Provider: " + scope, HttpStatus.INTERNAL_SERVER_ERROR));
    }

    public synchronized ScopeSettingsView updateScope(String scope, UpdateScopeRequest request, String updatedBy) {
        String normalizedScope = normalizeScope(scope);
        validateUpdateRequest(normalizedScope, request);

        ScopeSettingsView existingScope = resolveScope(normalizedScope, currentSettings);
        Map<String, ProviderConfigView> defaults = toProviderMap(existingScope.providers());
        String activeProvider = normalizeProvider(request.activeProvider());

        for (ProviderUpsertRequest item : request.providers()) {
            String provider = normalizeProvider(item.provider());
            ProviderConfigView fallback = defaults.get(provider);
            if (fallback == null) {
                throw new CustomException("不支持的 Provider: " + provider, HttpStatus.BAD_REQUEST);
            }

            validateProviderSecret(normalizedScope, provider, item);

            ModelProviderConfig entity = repository.findByConfigScopeAndProviderCode(normalizedScope, provider)
                    .orElseGet(ModelProviderConfig::new);
            entity.setConfigScope(normalizedScope);
            entity.setProviderCode(provider);
            entity.setDisplayName(fallback.displayName());
            entity.setApiStyle(fallback.apiStyle());
            entity.setApiBaseUrl(requireNonBlank(item.apiBaseUrl(), fallback.apiBaseUrl(), provider + " API 地址不能为空"));
            entity.setModelName(requireNonBlank(item.model(), fallback.model(), provider + " 模型或 AppKey 不能为空"));
            entity.setEmbeddingDimension((SCOPE_EMBEDDING.equals(normalizedScope) || SCOPE_VISION_EMBEDDING.equals(normalizedScope))
                    ? Optional.ofNullable(item.dimension()).orElse(fallback.dimension())
                    : null);
            entity.setEnabled(item.enabled() == null ? fallback.enabled() : item.enabled());
            entity.setActive(provider.equals(activeProvider));
            entity.setUpdatedBy(updatedBy);
            entity.setApiKeyCiphertext(resolveCiphertext(normalizedScope, provider, item.apiKey(), fallback.hasApiKey()));
            entity.setSecondaryApiKeyCiphertext(resolveSecondaryCiphertext(normalizedScope, provider, item.secondaryApiKey(), fallback.hasSecondaryApiKey()));
            repository.save(entity);
        }

        repository.findByConfigScopeOrderByProviderCodeAsc(normalizedScope).forEach(entity -> {
            boolean shouldBeActive = entity.getProviderCode().equals(activeProvider);
            if (entity.isActive() != shouldBeActive) {
                entity.setActive(shouldBeActive);
                entity.setUpdatedBy(updatedBy);
                repository.save(entity);
            }
        });

        reloadSettings();
        return resolveScope(normalizedScope, currentSettings);
    }

    public ConnectivityTestView testConnection(String scope, ProviderConnectionTestRequest request) {
        String normalizedScope = normalizeScope(scope);
        validateConnectionTestRequest(normalizedScope, request);

        String provider = normalizeProvider(request.provider());
        String apiKey = firstNonBlank(request.apiKey(), resolvePersistedOrDefaultApiKey(normalizedScope, provider));
        String secondaryApiKey = firstNonBlank(request.secondaryApiKey(), resolvePersistedOrDefaultSecondaryApiKey(normalizedScope, provider));

        long startAt = System.currentTimeMillis();
        try {
            if (SCOPE_OCR.equals(normalizedScope)) {
                testOcrConnection(request.apiBaseUrl(), apiKey, secondaryApiKey);
            } else if (SCOPE_SPEECH_TRANSCRIPTION.equals(normalizedScope)) {
                ProviderConfigView providerConfig = resolveProviderConfig(normalizedScope, provider);
                if (API_STYLE_LOCAL_FUNASR.equals(providerConfig.apiStyle())) {
                    testLocalFunasrConnection(request.apiBaseUrl());
                } else {
                    new com.alibaba.nls.client.AccessToken(apiKey.trim(), secondaryApiKey.trim()).apply();
                }
            } else if (SCOPE_VISION_EMBEDDING.equals(normalizedScope)) {
                testVisionEmbeddingConnection(request.apiBaseUrl(), request.model(), apiKey, request.dimension());
            } else {
                testOpenAiCompatibleConnection(normalizedScope, request, apiKey);
            }
            return new ConnectivityTestView(true, "连接测试成功", System.currentTimeMillis() - startAt);
        } catch (Exception exception) {
            return new ConnectivityTestView(false, exception.getMessage(), System.currentTimeMillis() - startAt);
        }
    }

    public synchronized void reloadSettings() {
        this.currentSettings = mergeOverrides(buildDefaultSettings(), repository.findAll());
    }

    private ModelProviderSettingsView buildDefaultSettings() {
        ScopeSettingsView llm = new ScopeSettingsView(SCOPE_LLM, "deepseek", List.of(
                new ProviderConfigView("deepseek", "DeepSeek", API_STYLE_OPENAI, deepSeekApiUrl, deepSeekModel, null, true, true,
                        hasValue(deepSeekApiKey), secretCryptoService.mask(deepSeekApiKey), false, ""),
                new ProviderConfigView("qwen", "Qwen", API_STYLE_OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", null, true, false, false, "", false, ""),
                new ProviderConfigView("zhipu", "ZhipuAI", API_STYLE_OPENAI, "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-air", null, true, false, false, "", false, "")
        ));

        ScopeSettingsView embedding = new ScopeSettingsView(SCOPE_EMBEDDING, "aliyun", List.of(
                new ProviderConfigView("aliyun", "Alibaba Cloud", API_STYLE_OPENAI, embeddingApiUrl, embeddingModel, embeddingDimension, true, true,
                        hasValue(embeddingApiKey), secretCryptoService.mask(embeddingApiKey), false, ""),
                new ProviderConfigView("zhipu", "ZhipuAI", API_STYLE_OPENAI, "https://open.bigmodel.cn/api/paas/v4", "embedding-3", 2048, true, false, false, "", false, "")
        ));

        ScopeSettingsView ocr = new ScopeSettingsView(SCOPE_OCR, OCR_PROVIDER_ALIYUN, List.of(
                new ProviderConfigView(OCR_PROVIDER_ALIYUN, "Alibaba OCR", API_STYLE_ALIYUN_OCR, ocrEndpoint, OCR_MODEL_RECOGNIZE_GENERAL, null, true, true,
                        hasValue(ocrAccessKeyId), secretCryptoService.mask(ocrAccessKeyId),
                        hasValue(ocrAccessKeySecret), secretCryptoService.mask(ocrAccessKeySecret))
        ));

        ScopeSettingsView visionEmbedding = new ScopeSettingsView(SCOPE_VISION_EMBEDDING, "aliyun", List.of(
                new ProviderConfigView("aliyun", "DashScope Multimodal Embedding", API_STYLE_ALIYUN_DASHSCOPE, visionEmbeddingApiUrl, visionEmbeddingModel, visionEmbeddingDimension, true, true,
                        hasValue(firstNonBlank(visionEmbeddingApiKey, embeddingApiKey)), secretCryptoService.mask(firstNonBlank(visionEmbeddingApiKey, embeddingApiKey)), false, "")
        ));

        ScopeSettingsView multimodalLlm = new ScopeSettingsView(SCOPE_MULTIMODAL_LLM, "qwen", List.of(
                new ProviderConfigView("qwen", "Qwen-VL", API_STYLE_OPENAI, multimodalLlmApiUrl, multimodalLlmModel, null, true, true,
                        hasValue(firstNonBlank(multimodalLlmApiKey, embeddingApiKey)), secretCryptoService.mask(firstNonBlank(multimodalLlmApiKey, embeddingApiKey)), false, "")
        ));

        ScopeSettingsView speech = new ScopeSettingsView(SCOPE_SPEECH_TRANSCRIPTION, "funasr", List.of(
                new ProviderConfigView("aliyun", "Alibaba NLS Flash ASR", API_STYLE_ALIYUN_NLS_FLASH_ASR, speechTranscriptionApiUrl, speechTranscriptionAppKey, null, true, true,
                        hasValue(speechTranscriptionAccessKeyId), secretCryptoService.mask(speechTranscriptionAccessKeyId),
                        hasValue(speechTranscriptionAccessKeySecret), secretCryptoService.mask(speechTranscriptionAccessKeySecret)),
                new ProviderConfigView("funasr", "Local FunASR", API_STYLE_LOCAL_FUNASR, localFunasrApiUrl, localFunasrModel, null, true, false,
                        false, "", false, "")
        ));

        return new ModelProviderSettingsView(llm, embedding, ocr, visionEmbedding, multimodalLlm, speech);
    }

    private ModelProviderSettingsView mergeOverrides(ModelProviderSettingsView defaults, List<ModelProviderConfig> configs) {
        return new ModelProviderSettingsView(
                mergeScope(defaults.llm(), configs),
                mergeScope(defaults.embedding(), configs),
                mergeScope(defaults.ocr(), configs),
                mergeScope(defaults.visionEmbedding(), configs),
                mergeScope(defaults.multimodalLlm(), configs),
                mergeScope(defaults.speechTranscription(), configs));
    }

    private ScopeSettingsView mergeScope(ScopeSettingsView defaults, List<ModelProviderConfig> configs) {
        Map<String, ProviderConfigView> merged = toProviderMap(defaults.providers());
        String activeProvider = defaults.activeProvider();

        for (ModelProviderConfig config : configs) {
            if (config == null || !defaults.scope().equals(config.getConfigScope())) {
                continue;
            }
            ProviderConfigView fallback = merged.get(config.getProviderCode());
            if (fallback == null) {
                continue;
            }
            String apiKey = secretCryptoService.decrypt(config.getApiKeyCiphertext());
            String secondaryApiKey = secretCryptoService.decrypt(config.getSecondaryApiKeyCiphertext());
            merged.put(config.getProviderCode(), new ProviderConfigView(
                    config.getProviderCode(),
                    fallback.displayName(),
                    fallback.apiStyle(),
                    firstNonBlank(config.getApiBaseUrl(), fallback.apiBaseUrl()),
                    firstNonBlank(config.getModelName(), fallback.model()),
                    config.getEmbeddingDimension() != null ? config.getEmbeddingDimension() : fallback.dimension(),
                    config.isEnabled(),
                    config.isActive(),
                    hasValue(apiKey),
                    secretCryptoService.mask(apiKey),
                    hasValue(secondaryApiKey),
                    secretCryptoService.mask(secondaryApiKey)));
            if (config.isActive()) {
                activeProvider = config.getProviderCode();
            }
        }

        List<ProviderConfigView> providers = new ArrayList<>(merged.values());
        providers.sort(Comparator.comparing(ProviderConfigView::provider));
        return new ScopeSettingsView(defaults.scope(), activeProvider, providers);
    }

    private ActiveProviderView toActiveProvider(String scope, ProviderConfigView provider) {
        Optional<ModelProviderConfig> persisted = repository.findByConfigScopeAndProviderCode(scope, provider.provider());
        String apiKey = persisted.map(ModelProviderConfig::getApiKeyCiphertext)
                .map(secretCryptoService::decrypt)
                .orElseGet(() -> resolveDefaultApiKey(scope, provider.provider()));
        String secondaryApiKey = persisted.map(ModelProviderConfig::getSecondaryApiKeyCiphertext)
                .map(secretCryptoService::decrypt)
                .orElseGet(() -> resolveDefaultSecondaryApiKey(scope, provider.provider()));
        return new ActiveProviderView(provider.provider(), provider.displayName(), provider.apiStyle(), provider.apiBaseUrl(), provider.model(), apiKey, secondaryApiKey, provider.dimension());
    }

    private Map<String, ProviderConfigView> toProviderMap(List<ProviderConfigView> providers) {
        Map<String, ProviderConfigView> result = new LinkedHashMap<>();
        for (ProviderConfigView provider : providers) {
            result.put(provider.provider(), provider);
        }
        return result;
    }

    private void validateUpdateRequest(String scope, UpdateScopeRequest request) {
        if (request == null || request.providers() == null || request.providers().isEmpty()) {
            throw new CustomException("Provider 配置不能为空", HttpStatus.BAD_REQUEST);
        }
        String activeProvider = normalizeProvider(request.activeProvider());
        boolean activeExists = false;
        boolean activeEnabled = false;

        for (ProviderUpsertRequest provider : request.providers()) {
            String providerCode = normalizeProvider(provider.provider());
            if (providerCode.equals(activeProvider)) {
                activeExists = true;
                activeEnabled = provider.enabled() == null || provider.enabled();
            }
            if ((SCOPE_EMBEDDING.equals(scope) || SCOPE_VISION_EMBEDDING.equals(scope))
                    && provider.dimension() != null && provider.dimension() <= 0) {
                throw new CustomException(providerCode + " 向量维度必须大于 0", HttpStatus.BAD_REQUEST);
            }
        }
        if (!activeExists) {
            throw new CustomException("当前启用 Provider 必须在配置列表中", HttpStatus.BAD_REQUEST);
        }
        if (!activeEnabled) {
            throw new CustomException("当前启用 Provider 不能被禁用", HttpStatus.BAD_REQUEST);
        }
    }

    private void validateProviderSecret(String scope, String provider, ProviderUpsertRequest request) {
        if (isLocalFunasr(scope, provider)) {
            return;
        }
        if (SCOPE_OCR.equals(scope) || SCOPE_SPEECH_TRANSCRIPTION.equals(scope)) {
            String apiKey = firstNonBlank(request.apiKey(), resolvePersistedOrDefaultApiKey(scope, provider));
            String secondaryApiKey = firstNonBlank(request.secondaryApiKey(), resolvePersistedOrDefaultSecondaryApiKey(scope, provider));
            String label = SCOPE_OCR.equals(scope) ? "OCR" : "Speech";
            if (!hasValue(apiKey)) {
                throw new CustomException(provider + " " + label + " AccessKeyId不能为空", HttpStatus.BAD_REQUEST);
            }
            if (!hasValue(secondaryApiKey)) {
                throw new CustomException(provider + " " + label + " AccessKeySecret不能为空", HttpStatus.BAD_REQUEST);
            }
        }
    }

    private void validateConnectionTestRequest(String scope, ProviderConnectionTestRequest request) {
        if (request == null) {
            throw new CustomException("测试连接参数不能为空", HttpStatus.BAD_REQUEST);
        }
        if (!hasValue(request.provider())) {
            throw new CustomException("Provider不能为空", HttpStatus.BAD_REQUEST);
        }
        if (!hasValue(request.apiBaseUrl())) {
            throw new CustomException("API 地址不能为空", HttpStatus.BAD_REQUEST);
        }
        if (!hasValue(request.model())) {
            throw new CustomException("模型或 AppKey 不能为空", HttpStatus.BAD_REQUEST);
        }
        if ((SCOPE_EMBEDDING.equals(scope) || SCOPE_VISION_EMBEDDING.equals(scope))
                && request.dimension() != null && request.dimension() <= 0) {
            throw new CustomException("向量维度必须大于 0", HttpStatus.BAD_REQUEST);
        }

        String provider = normalizeProvider(request.provider());
        String apiKey = firstNonBlank(request.apiKey(), resolvePersistedOrDefaultApiKey(scope, provider));
        String secondaryApiKey = firstNonBlank(request.secondaryApiKey(), resolvePersistedOrDefaultSecondaryApiKey(scope, provider));
        if (isLocalFunasr(scope, provider)) {
            return;
        }
        if (SCOPE_OCR.equals(scope) || SCOPE_SPEECH_TRANSCRIPTION.equals(scope)) {
            if (!hasValue(apiKey)) {
                throw new CustomException("AccessKeyId不能为空", HttpStatus.BAD_REQUEST);
            }
            if (!hasValue(secondaryApiKey)) {
                throw new CustomException("AccessKeySecret不能为空", HttpStatus.BAD_REQUEST);
            }
        } else if (!hasValue(apiKey)) {
            throw new CustomException("API Key不能为空", HttpStatus.BAD_REQUEST);
        }
    }

    private void testOpenAiCompatibleConnection(String scope, ProviderConnectionTestRequest request, String apiKey) {
        WebClient client = WebClient.builder()
                .baseUrl(request.apiBaseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();

        if (SCOPE_LLM.equals(scope) || SCOPE_MULTIMODAL_LLM.equals(scope)) {
            Map<String, Object> payload = Map.of(
                    "model", request.model(),
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "stream", false,
                    "max_tokens", 1);
            client.post().uri("/chat/completions").bodyValue(payload).retrieve().bodyToMono(String.class).block(Duration.ofSeconds(8));
        } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", request.model());
            payload.put("input", List.of("ping"));
            payload.put("encoding_format", "float");
            if (request.dimension() != null) {
                payload.put("dimension", request.dimension());
            }
            client.post().uri("/embeddings").bodyValue(payload).retrieve().bodyToMono(String.class).block(Duration.ofSeconds(8));
        }
    }

    private void testOcrConnection(String apiBaseUrl, String accessKeyId, String accessKeySecret) throws Exception {
        Config config = new Config();
        config.setAccessKeyId(accessKeyId.trim());
        config.setAccessKeySecret(accessKeySecret.trim());
        config.setEndpoint(normalizeOcrEndpoint(apiBaseUrl));

        RecognizeGeneralRequest request = new RecognizeGeneralRequest()
                .setBody(new ByteArrayInputStream(OCR_TEST_IMAGE_BYTES));

        RuntimeOptions runtimeOptions = new RuntimeOptions();
        runtimeOptions.setConnectTimeout(8000);
        runtimeOptions.setReadTimeout(8000);
        new Client(config).recognizeGeneralWithOptions(request, runtimeOptions);
    }

    private void testVisionEmbeddingConnection(String apiBaseUrl, String model, String apiKey, Integer dimension) {
        Map<String, Object> input = Map.of("contents", List.of(Map.of("text", "ping")));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("output_type", "dense");
        if (dimension != null) {
            parameters.put("dimension", dimension);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", input);
        payload.put("parameters", parameters);

        WebClient.builder()
                .baseUrl(apiBaseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build()
                .post()
                .uri("/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(8));
    }

    private void testLocalFunasrConnection(String apiBaseUrl) {
        WebClient.builder()
                .baseUrl(normalizeLocalFunasrBaseUrl(apiBaseUrl))
                .build()
                .get()
                .uri("/health")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(8));
    }

    private ScopeSettingsView resolveScope(String scope, ModelProviderSettingsView settings) {
        return switch (normalizeScope(scope)) {
            case SCOPE_LLM -> settings.llm();
            case SCOPE_EMBEDDING -> settings.embedding();
            case SCOPE_OCR -> settings.ocr();
            case SCOPE_VISION_EMBEDDING -> settings.visionEmbedding();
            case SCOPE_MULTIMODAL_LLM -> settings.multimodalLlm();
            case SCOPE_SPEECH_TRANSCRIPTION -> settings.speechTranscription();
            default -> throw new CustomException("不支持的配置范围: " + scope, HttpStatus.BAD_REQUEST);
        };
    }

    private String normalizeScope(String scope) {
        String normalized = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        if (!SCOPE_LLM.equals(normalized)
                && !SCOPE_EMBEDDING.equals(normalized)
                && !SCOPE_OCR.equals(normalized)
                && !SCOPE_VISION_EMBEDDING.equals(normalized)
                && !SCOPE_MULTIMODAL_LLM.equals(normalized)
                && !SCOPE_SPEECH_TRANSCRIPTION.equals(normalized)) {
            throw new CustomException("不支持的配置范围: " + scope, HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private String normalizeProvider(String provider) {
        if (!hasValue(provider)) {
            throw new CustomException("Provider不能为空", HttpStatus.BAD_REQUEST);
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private String resolveCiphertext(String scope, String provider, String rawApiKey, boolean hasFallbackSecret) {
        if (hasValue(rawApiKey)) {
            return secretCryptoService.encrypt(rawApiKey.trim());
        }
        Optional<ModelProviderConfig> persisted = repository.findByConfigScopeAndProviderCode(scope, provider);
        if (persisted.isPresent() && hasValue(persisted.get().getApiKeyCiphertext())) {
            return persisted.get().getApiKeyCiphertext();
        }
        return hasFallbackSecret ? secretCryptoService.encrypt(resolveDefaultApiKey(scope, provider)) : null;
    }

    private String resolveSecondaryCiphertext(String scope, String provider, String rawSecondaryApiKey, boolean hasFallbackSecret) {
        if (hasValue(rawSecondaryApiKey)) {
            return secretCryptoService.encrypt(rawSecondaryApiKey.trim());
        }
        Optional<ModelProviderConfig> persisted = repository.findByConfigScopeAndProviderCode(scope, provider);
        if (persisted.isPresent() && hasValue(persisted.get().getSecondaryApiKeyCiphertext())) {
            return persisted.get().getSecondaryApiKeyCiphertext();
        }
        return hasFallbackSecret ? secretCryptoService.encrypt(resolveDefaultSecondaryApiKey(scope, provider)) : null;
    }

    private String resolvePersistedOrDefaultApiKey(String scope, String provider) {
        return repository.findByConfigScopeAndProviderCode(scope, provider)
                .map(ModelProviderConfig::getApiKeyCiphertext)
                .map(secretCryptoService::decrypt)
                .orElseGet(() -> resolveDefaultApiKey(scope, provider));
    }

    private String resolvePersistedOrDefaultSecondaryApiKey(String scope, String provider) {
        return repository.findByConfigScopeAndProviderCode(scope, provider)
                .map(ModelProviderConfig::getSecondaryApiKeyCiphertext)
                .map(secretCryptoService::decrypt)
                .orElseGet(() -> resolveDefaultSecondaryApiKey(scope, provider));
    }

    private String resolveDefaultApiKey(String scope, String provider) {
        if (SCOPE_LLM.equals(scope) && "deepseek".equals(provider)) {
            return deepSeekApiKey;
        }
        if (SCOPE_EMBEDDING.equals(scope) && "aliyun".equals(provider)) {
            return embeddingApiKey;
        }
        if (SCOPE_VISION_EMBEDDING.equals(scope) && "aliyun".equals(provider)) {
            return firstNonBlank(visionEmbeddingApiKey, embeddingApiKey);
        }
        if (SCOPE_OCR.equals(scope) && OCR_PROVIDER_ALIYUN.equals(provider)) {
            return ocrAccessKeyId;
        }
        if (SCOPE_MULTIMODAL_LLM.equals(scope) && "qwen".equals(provider)) {
            return firstNonBlank(multimodalLlmApiKey, embeddingApiKey);
        }
        if (SCOPE_SPEECH_TRANSCRIPTION.equals(scope) && "aliyun".equals(provider)) {
            return speechTranscriptionAccessKeyId;
        }
        return null;
    }

    private String resolveDefaultSecondaryApiKey(String scope, String provider) {
        if (SCOPE_OCR.equals(scope) && OCR_PROVIDER_ALIYUN.equals(provider)) {
            return ocrAccessKeySecret;
        }
        if (SCOPE_SPEECH_TRANSCRIPTION.equals(scope) && "aliyun".equals(provider)) {
            return speechTranscriptionAccessKeySecret;
        }
        return null;
    }

    private String normalizeOcrEndpoint(String apiBaseUrl) {
        String value = requireNonBlank(apiBaseUrl, null, "OCR API 地址不能为空").trim();
        value = value.replaceFirst("^https?://", "");
        int slashIndex = value.indexOf('/');
        return slashIndex >= 0 ? value.substring(0, slashIndex) : value;
    }

    private String normalizeLocalFunasrBaseUrl(String apiBaseUrl) {
        String value = requireNonBlank(apiBaseUrl, null, "Local FunASR API URL cannot be blank").trim();
        if (value.endsWith("/transcribe")) {
            return value.substring(0, value.length() - "/transcribe".length());
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private boolean isLocalFunasr(String scope, String provider) {
        if (!SCOPE_SPEECH_TRANSCRIPTION.equals(scope)) {
            return false;
        }
        try {
            return API_STYLE_LOCAL_FUNASR.equals(resolveProviderConfig(scope, provider).apiStyle());
        } catch (RuntimeException exception) {
            return "funasr".equals(provider);
        }
    }

    private ProviderConfigView resolveProviderConfig(String scope, String provider) {
        return resolveScope(scope, currentSettings).providers().stream()
                .filter(item -> item.provider().equals(provider))
                .findFirst()
                .orElseThrow(() -> new CustomException("Unsupported Provider: " + provider, HttpStatus.BAD_REQUEST));
    }

    private String requireNonBlank(String candidate, String fallback, String message) {
        String value = hasValue(candidate) ? candidate.trim() : fallback;
        if (!hasValue(value)) {
            throw new CustomException(message, HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    private String firstNonBlank(String first, String second) {
        return hasValue(first) ? first.trim() : hasValue(second) ? second.trim() : null;
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    public record ModelProviderSettingsView(
            ScopeSettingsView llm,
            ScopeSettingsView embedding,
            ScopeSettingsView ocr,
            ScopeSettingsView visionEmbedding,
            ScopeSettingsView multimodalLlm,
            ScopeSettingsView speechTranscription
    ) {
    }

    public record ScopeSettingsView(
            String scope,
            String activeProvider,
            List<ProviderConfigView> providers
    ) {
    }

    public record ProviderConfigView(
            String provider,
            String displayName,
            String apiStyle,
            String apiBaseUrl,
            String model,
            Integer dimension,
            boolean enabled,
            boolean active,
            boolean hasApiKey,
            String maskedApiKey,
            boolean hasSecondaryApiKey,
            String maskedSecondaryApiKey
    ) {
    }

    public record ProviderUpsertRequest(
            String provider,
            String apiBaseUrl,
            String model,
            String apiKey,
            String secondaryApiKey,
            Integer dimension,
            Boolean enabled
    ) {
    }

    public record UpdateScopeRequest(
            String activeProvider,
            List<ProviderUpsertRequest> providers
    ) {
    }

    public record ProviderConnectionTestRequest(
            String provider,
            String apiBaseUrl,
            String model,
            String apiKey,
            String secondaryApiKey,
            Integer dimension
    ) {
    }

    public record ConnectivityTestView(
            boolean success,
            String message,
            long latencyMs
    ) {
    }

    public record ActiveProviderView(
            String provider,
            String displayName,
            String apiStyle,
            String apiBaseUrl,
            String model,
            String apiKey,
            String secondaryApiKey,
            Integer dimension
    ) {
    }
}
