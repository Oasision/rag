<script setup lang="ts">
import { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from '@/vendor/vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const { conversationId, list, sessionId } = storeToRefs(chatStore);

const loading = ref(false);
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

watch(() => [...list.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

const range = ref<[number, number] | null>(null);

function getRetrievalQueryFallback(index: number) {
  for (let i = index - 1; i >= 0; i -= 1) {
    const candidate = list.value[i];
    if (candidate?.role === 'user') {
      return candidate.content || '';
    }
  }
  return '';
}

const params = computed<Record<string, string>>(() => {
  if (!range.value) {
    return {} as Record<string, string>;
  }

  return {
    start_date: dayjs(range.value[0]).format('YYYY-MM-DD'),
    end_date: dayjs(range.value[1]).format('YYYY-MM-DD')
  };
});

watch(
  [conversationId, params],
  () => {
    getList();
  },
  { immediate: true }
);

async function getList() {
  if (!conversationId.value) {
    list.value = [];
    return;
  }

  loading.value = true;
  await chatStore.loadMessages(params.value);
  loading.value = false;
}

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
});
</script>

<template>
  <Suspense>
    <NScrollbar ref="scrollbarRef" class="h-0 flex-auto">
      <Teleport defer to="#header-extra">
        <div class="chat-header-filter">
          <NForm :model="params" label-placement="left" :show-feedback="false" inline>
            <NFormItem label="时间">
              <NDatePicker v-model:value="range" type="daterange" clearable />
            </NFormItem>
          </NForm>
        </div>
      </Teleport>
      <NSpin :show="loading">
        <VueMarkdownItProvider>
          <ChatMessage
            v-for="(item, index) in list"
            :key="index"
            :msg="item"
            :session-id="sessionId"
            :retrieval-query-fallback="getRetrievalQueryFallback(index)"
          />
        </VueMarkdownItProvider>
      </NSpin>
    </NScrollbar>
  </Suspense>
</template>

<style scoped lang="scss">
.chat-header-filter {
  padding: 7px 14px;
  border: 1px solid rgb(255 255 255 / 0.72);
  border-radius: 999px;
  background: linear-gradient(180deg, rgb(255 255 255 / 0.82), rgb(239 246 255 / 0.62));
  box-shadow:
    0 14px 34px rgb(15 23 42 / 0.1),
    0 0 0 1px rgb(255 255 255 / 0.46) inset;
  backdrop-filter: blur(16px) saturate(1.16);
  transform: translateX(18px);
}

.chat-header-filter :deep(.n-form-item) {
  margin-bottom: 0;
}
</style>
