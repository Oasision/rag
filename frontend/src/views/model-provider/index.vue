<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { NButton, NCard, NEmpty, NInput, NInputNumber, NSelect, NSpin, NSwitch } from 'naive-ui';

type ScopeKey = 'llm' | 'embedding' | 'ocr' | 'visionEmbedding' | 'multimodalLlm' | 'speechTranscription';
type BackendScope = 'llm' | 'embedding' | 'ocr' | 'vision_embedding' | 'multimodal_llm' | 'speech_transcription';

interface ScopeMeta {
  backendScope: BackendScope;
  title: string;
  subtitle: string;
  saveText: string;
  modelLabel: string;
}

const scopeOrder: ScopeKey[] = ['llm', 'embedding', 'ocr', 'visionEmbedding', 'multimodalLlm', 'speechTranscription'];

const scopeMetaMap: Record<ScopeKey, ScopeMeta> = {
  llm: {
    backendScope: 'llm',
    title: 'LLM Provider',
    subtitle: '纯文本问答仍会优先走这里，保持当前 txt / 文档问答链路不变。',
    saveText: '保存 LLM 配置',
    modelLabel: '模型'
  },
  embedding: {
    backendScope: 'embedding',
    title: 'Embedding Provider',
    subtitle: '文本切片向量化仍使用这里，避免影响当前知识库文本检索。',
    saveText: '保存 Embedding 配置',
    modelLabel: '模型'
  },
  ocr: {
    backendScope: 'ocr',
    title: 'OCR Provider',
    subtitle: '图片和扫描版 PDF 的文本提取依赖 OCR，需要同时配置 AccessKeyId 和 AccessKeySecret。',
    saveText: '保存 OCR 配置',
    modelLabel: 'OCR 方法'
  },
  visionEmbedding: {
    backendScope: 'vision_embedding',
    title: 'Vision Embedding Provider',
    subtitle: '图片文件会额外写入图像向量，供多模态检索命中图片证据时使用。',
    saveText: '保存图像向量配置',
    modelLabel: '向量模型'
  },
  speechTranscription: {
    backendScope: 'speech_transcription',
    title: 'Speech Transcription Provider',
    subtitle: 'MP3 / MP4 等音视频文件会先通过 ASR 转写成带时间戳的文本，再进入原有文本向量化和 RAG 检索链路。',
    saveText: '保存音视频转写配置',
    modelLabel: 'AppKey'
  },
  multimodalLlm: {
    backendScope: 'multimodal_llm',
    title: 'Multimodal LLM Provider',
    subtitle: '当问题命中图片证据时，系统会按规则自动切换到多模态大模型。',
    saveText: '保存多模态模型配置',
    modelLabel: '多模态模型'
  }
};

const modelProvidersLoading = ref(false);
const modelProvidersSaving = ref(false);
const modelProviders = ref<Api.Admin.ModelProviderSettings | null>(null);

function createProviderItem(config: {
  provider: string;
  displayName: string;
  apiStyle: string;
  apiBaseUrl: string;
  model: string;
  dimension: number | null;
}): Api.Admin.ModelProviderItem {
  return {
    provider: config.provider,
    displayName: config.displayName,
    apiStyle: config.apiStyle,
    apiBaseUrl: config.apiBaseUrl,
    model: config.model,
    dimension: config.dimension,
    enabled: true,
    active: true,
    hasApiKey: false,
    maskedApiKey: '',
    hasSecondaryApiKey: false,
    maskedSecondaryApiKey: '',
    apiKeyInput: '',
    secondaryApiKeyInput: ''
  };
}

function createFallbackScope(scopeKey: ScopeKey): Api.Admin.ModelProviderScopeSettings {
  if (scopeKey === 'ocr') {
    return {
      scope: 'ocr',
      activeProvider: 'aliyun',
      providers: [
        createProviderItem({
          provider: 'aliyun',
          displayName: 'Alibaba OCR',
          apiStyle: 'aliyun-ocr',
          apiBaseUrl: 'ocr-api.cn-hangzhou.aliyuncs.com',
          model: 'RecognizeGeneral',
          dimension: null
        })
      ]
    };
  }

  if (scopeKey === 'visionEmbedding') {
    return {
      scope: 'vision_embedding',
      activeProvider: 'aliyun',
      providers: [
        createProviderItem({
          provider: 'aliyun',
          displayName: 'DashScope Multimodal Embedding',
          apiStyle: 'aliyun-dashscope',
          apiBaseUrl: 'https://dashscope.aliyuncs.com',
          model: 'multimodal-embedding-v1',
          dimension: 1024
        })
      ]
    };
  }

  if (scopeKey === 'speechTranscription') {
    return {
      scope: 'speech_transcription',
      activeProvider: 'funasr',
      providers: [
        createProviderItem({
          provider: 'aliyun',
          displayName: 'Alibaba NLS Flash ASR',
          apiStyle: 'aliyun-nls-flash-asr',
          apiBaseUrl: 'https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/FlashRecognizer',
          model: '',
          dimension: null
        }),
        createProviderItem({
          provider: 'funasr',
          displayName: 'Local FunASR',
          apiStyle: 'local-funasr',
          apiBaseUrl: 'http://127.0.0.1:9880',
          model: 'paraformer-zh',
          dimension: null
        })
      ]
    };
  }

  return {
    scope: 'multimodal_llm',
    activeProvider: 'qwen',
    providers: [
      createProviderItem({
        provider: 'qwen',
        displayName: 'Qwen-VL',
        apiStyle: 'openai-compatible',
        apiBaseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        model: 'qwen-vl-plus',
        dimension: null
      })
    ]
  };
}

function cloneProviderItem(item: Api.Admin.ModelProviderItem): Api.Admin.ModelProviderItem {
  return {
    provider: item.provider,
    displayName: item.displayName,
    apiStyle: item.apiStyle,
    apiBaseUrl: item.apiBaseUrl,
    model: item.model,
    dimension: item.dimension ?? null,
    enabled: Boolean(item.enabled),
    active: Boolean(item.active),
    hasApiKey: Boolean(item.hasApiKey),
    maskedApiKey: item.maskedApiKey || '',
    hasSecondaryApiKey: Boolean(item.hasSecondaryApiKey),
    maskedSecondaryApiKey: item.maskedSecondaryApiKey || '',
    apiKeyInput: '',
    secondaryApiKeyInput: ''
  };
}

function cloneModelProviderScope(payload: Api.Admin.ModelProviderScopeSettings): Api.Admin.ModelProviderScopeSettings {
  return {
    scope: payload.scope,
    activeProvider: payload.activeProvider,
    providers: (payload.providers || []).map(cloneProviderItem)
  };
}

function cloneModelProviderSettings(
  payload?: Api.Admin.ModelProviderSettings | null
): Api.Admin.ModelProviderSettings | null {
  if (!payload) {
    return null;
  }

  return {
    llm: cloneModelProviderScope(payload.llm),
    embedding: cloneModelProviderScope(payload.embedding),
    ocr: payload.ocr ? cloneModelProviderScope(payload.ocr) : createFallbackScope('ocr'),
    visionEmbedding: payload.visionEmbedding
      ? cloneModelProviderScope(payload.visionEmbedding)
      : createFallbackScope('visionEmbedding'),
    multimodalLlm: payload.multimodalLlm
      ? cloneModelProviderScope(payload.multimodalLlm)
      : createFallbackScope('multimodalLlm'),
    speechTranscription: payload.speechTranscription
      ? cloneModelProviderScope(payload.speechTranscription)
      : createFallbackScope('speechTranscription')
  };
}

function isDimensionScope(scopeKey: ScopeKey) {
  return scopeKey === 'embedding' || scopeKey === 'visionEmbedding';
}

function isOcrScope(scopeKey: ScopeKey) {
  return scopeKey === 'ocr';
}

function isSpeechTranscriptionScope(scopeKey: ScopeKey) {
  return scopeKey === 'speechTranscription';
}

function isAccessKeyPairScope(scopeKey: ScopeKey) {
  return isOcrScope(scopeKey) || isSpeechTranscriptionScope(scopeKey);
}

function isSecretlessProvider(provider: Api.Admin.ModelProviderItem) {
  return provider.apiStyle === 'local-funasr';
}

function currentScope(scopeKey: ScopeKey) {
  return modelProviders.value?.[scopeKey] ?? null;
}

async function getModelProviders() {
  modelProvidersLoading.value = true;
  try {
    const { error, data } = await request<Api.Admin.ModelProviderSettings>({
      url: '/admin/model-providers'
    });

    if (!error && data) {
      modelProviders.value = cloneModelProviderSettings(data);
      if (!data.ocr || !data.visionEmbedding || !data.multimodalLlm || !data.speechTranscription) {
        window.$message?.warning('当前后端返回的模型配置还不完整，页面已用默认占位兜底，建议重新加载最新后端代码。');
      }
    }
  } catch {
    window.$message?.error('加载模型配置失败，请检查后端服务状态。');
  } finally {
    modelProvidersLoading.value = false;
  }
}

function buildProviderPayload(scopeKey: ScopeKey, scope: Api.Admin.ModelProviderScopeSettings) {
  return {
    activeProvider: scope.activeProvider,
    providers: scope.providers.map(item => ({
      provider: item.provider,
      apiBaseUrl: item.apiBaseUrl,
      model: item.model,
      apiKey: item.apiKeyInput?.trim() || '',
      secondaryApiKey: item.secondaryApiKeyInput?.trim() || '',
      dimension: isDimensionScope(scopeKey) ? item.dimension : null,
      enabled: item.enabled
    }))
  };
}

function getSaveSuccessMessage(scopeKey: ScopeKey) {
  if (scopeKey === 'llm') return 'LLM 配置已更新';
  if (scopeKey === 'embedding') return 'Embedding 配置已更新';
  if (scopeKey === 'ocr') return 'OCR 配置已更新';
  if (scopeKey === 'visionEmbedding') return '图像向量配置已更新';
  if (scopeKey === 'speechTranscription') return '音视频转写配置已更新';
  return '多模态模型配置已更新';
}

async function submitModelProviders(scopeKey: ScopeKey) {
  const scope = currentScope(scopeKey);
  if (!scope) {
    return;
  }

  modelProvidersSaving.value = true;
  const { error, data } = await request<Api.Admin.ModelProviderScopeSettings>({
    url: `/admin/model-providers/${scopeMetaMap[scopeKey].backendScope}`,
    method: 'put',
    data: buildProviderPayload(scopeKey, scope)
  });

  if (!error && data && modelProviders.value) {
    modelProviders.value[scopeKey] = cloneModelProviderScope(data);
    window.$message?.success(getSaveSuccessMessage(scopeKey));
  }
  modelProvidersSaving.value = false;
}

async function testModelProvider(scopeKey: ScopeKey, provider: Api.Admin.ModelProviderItem) {
  const { error, data } = await request<Api.Admin.ConnectivityTestResult>({
    url: `/admin/model-providers/${scopeMetaMap[scopeKey].backendScope}/test`,
    method: 'post',
    data: {
      provider: provider.provider,
      apiBaseUrl: provider.apiBaseUrl,
      model: provider.model,
      apiKey: provider.apiKeyInput?.trim() || '',
      secondaryApiKey: provider.secondaryApiKeyInput?.trim() || '',
      dimension: isDimensionScope(scopeKey) ? provider.dimension : null
    }
  });

  if (!error && data) {
    if (data.success) {
      window.$message?.success(`${provider.displayName} 连接成功，耗时 ${data.latencyMs}ms`);
    } else {
      window.$message?.error(`${provider.displayName} 连接失败：${data.message}`);
    }
  }
}

const scopeCards = computed(() =>
  scopeOrder.map(key => ({
    key,
    meta: scopeMetaMap[key],
    scope: currentScope(key)
  }))
);

onMounted(() => {
  getModelProviders();
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-auto">
    <NCard :bordered="false" size="small" class="card-wrapper">
      <template #header>模型 Provider 配置</template>
      <template #header-extra>
        <span class="text-xs text-stone-400">文本链路与多模态链路分开管理，留空密钥时会保留现有值，不回显明文。</span>
      </template>

      <NSpin :show="modelProvidersLoading">
        <div class="mb-4 border border-stone-200 rounded-2xl bg-stone-50 px-4 py-3 text-xs text-stone-500 leading-6">
          当前版本采用规则路由：纯文本问题继续走文本 Embedding + 文本
          LLM，命中图片证据时才会自动切到图像向量和多模态大模型。
        </div>

        <div v-if="modelProviders" class="grid gap-4">
          <div v-for="item in scopeCards" :key="item.key" class="provider-scope">
            <div class="provider-scope-header">
              <div>
                <div class="provider-scope-title">{{ item.meta.title }}</div>
                <div class="provider-scope-sub">{{ item.meta.subtitle }}</div>
              </div>
              <div v-if="item.scope" class="flex items-center gap-3">
                <NSelect
                  v-model:value="item.scope.activeProvider"
                  :options="
                    item.scope.providers.map(provider => ({
                      label: provider.displayName,
                      value: provider.provider,
                      disabled: !provider.enabled
                    }))
                  "
                  class="min-w-180px"
                />
                <NButton
                  type="primary"
                  size="small"
                  :loading="modelProvidersSaving"
                  @click="submitModelProviders(item.key)"
                >
                  {{ item.meta.saveText }}
                </NButton>
              </div>
            </div>

            <div v-if="item.scope" class="provider-grid">
              <div
                v-for="provider in item.scope.providers"
                :key="`${item.key}-${provider.provider}`"
                class="provider-card"
              >
                <div class="provider-card-header">
                  <div>
                    <div class="provider-name">{{ provider.displayName }}</div>
                    <div class="provider-code">{{ provider.provider }} · {{ provider.apiStyle }}</div>
                  </div>
                  <NSwitch v-model:value="provider.enabled" size="small" />
                </div>

                <div class="limit-grid">
                  <div>
                    <div class="limit-label">API 地址</div>
                    <NInput
                      v-model:value="provider.apiBaseUrl"
                      :placeholder="
                        item.key === 'ocr'
                          ? 'ocr-api.cn-hangzhou.aliyuncs.com'
                          : item.key === 'speechTranscription'
                            ? provider.apiStyle === 'local-funasr'
                              ? 'http://127.0.0.1:9880'
                              : 'https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/FlashRecognizer'
                            : 'https://dashscope.aliyuncs.com'
                      "
                    />
                  </div>
                  <div>
                    <div class="limit-label">{{ item.meta.modelLabel }}</div>
                    <NInput v-model:value="provider.model" />
                  </div>

                  <div v-if="isDimensionScope(item.key)">
                    <div class="limit-label">维度</div>
                    <NInputNumber v-model:value="provider.dimension" :min="1" class="w-full" />
                  </div>

                  <template v-if="isAccessKeyPairScope(item.key) && !isSecretlessProvider(provider)">
                    <div>
                      <div class="limit-label">现有 AccessKeyId</div>
                      <div class="provider-mask">{{ provider.hasApiKey ? provider.maskedApiKey : '未配置' }}</div>
                    </div>
                    <div>
                      <div class="limit-label">新 AccessKeyId</div>
                      <NInput
                        v-model:value="provider.apiKeyInput"
                        type="password"
                        show-password-on="click"
                        placeholder="留空则保留现有值"
                      />
                    </div>
                    <div>
                      <div class="limit-label">现有 AccessKeySecret</div>
                      <div class="provider-mask">
                        {{ provider.hasSecondaryApiKey ? provider.maskedSecondaryApiKey : '未配置' }}
                      </div>
                    </div>
                    <div>
                      <div class="limit-label">新 AccessKeySecret</div>
                      <NInput
                        v-model:value="provider.secondaryApiKeyInput"
                        type="password"
                        show-password-on="click"
                        placeholder="留空则保留现有值"
                      />
                    </div>
                  </template>

                  <template v-else-if="!isSecretlessProvider(provider)">
                    <div>
                      <div class="limit-label">现有 API Key</div>
                      <div class="provider-mask">{{ provider.hasApiKey ? provider.maskedApiKey : '未配置' }}</div>
                    </div>
                    <div :class="isDimensionScope(item.key) ? '' : 'sm:col-span-1'">
                      <div class="limit-label">新 API Key</div>
                      <NInput
                        v-model:value="provider.apiKeyInput"
                        type="password"
                        show-password-on="click"
                        placeholder="留空则保留现有值"
                      />
                    </div>
                  </template>

                  <template v-else>
                    <div class="sm:col-span-2">
                      <div class="limit-label">认证方式</div>
                      <div class="provider-mask">本地 FunASR 服务无需 AccessKey，保持服务运行即可。</div>
                    </div>
                  </template>
                </div>

                <div class="mt-3 flex justify-end">
                  <NButton size="small" secondary @click="testModelProvider(item.key, provider)">测试连接</NButton>
                </div>
              </div>
            </div>
          </div>
        </div>

        <NEmpty v-else size="small" description="暂未加载到模型配置" />
      </NSpin>
    </NCard>
  </div>
</template>

<style scoped lang="scss">
.provider-scope {
  @apply rounded-3xl border border-stone-200 bg-[linear-gradient(180deg,_rgba(255,255,255,0.98),_rgba(248,250,252,0.94))] p-5 shadow-sm;
}

.provider-scope-header {
  @apply mb-4 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between;
}

.provider-scope-title {
  @apply text-sm font-semibold text-stone-700;
}

.provider-scope-sub {
  @apply mt-1 text-xs text-stone-500;
}

.provider-grid {
  @apply grid gap-4 xl:grid-cols-2;
}

.provider-card {
  @apply rounded-2xl border border-stone-200 bg-white p-4 shadow-sm;
}

.provider-card-header {
  @apply mb-4 flex items-start justify-between gap-3;
}

.provider-name {
  @apply text-sm font-semibold text-stone-700;
}

.provider-code {
  @apply mt-1 text-xs text-stone-400;
}

.provider-mask {
  @apply rounded-xl border border-dashed border-stone-200 bg-stone-50 px-3 py-2 text-sm text-stone-500;
}

.limit-grid {
  @apply grid gap-3 sm:grid-cols-2;
}

.limit-label {
  @apply mb-2 text-xs font-semibold uppercase tracking-0.08em text-stone-400;
}
</style>
