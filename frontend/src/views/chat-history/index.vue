<script setup lang="ts">
import type { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from '@/vendor/vue-markdown-shiki';
import ChatMessage from '../chat/modules/chat-message.vue';

defineOptions({
  name: 'ChatHistory'
});

const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

const list = ref<Api.Chat.Message[]>([]);
const loading = ref(false);

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
const userId = ref<number | null>(null);

const params = computed(() => {
  const query: {
    userid?: number;
    start_date?: string;
    end_date?: string;
  } = {};

  if (userId.value !== null) {
    query.userid = userId.value;
  }

  if (range.value) {
    query.start_date = dayjs(range.value[0]).format('YYYY-MM-DD');
    query.end_date = dayjs(range.value[1]).format('YYYY-MM-DD');
  }

  return query;
});

watchEffect(() => {
  getList();
});

async function getList() {
  loading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'admin/conversation',
    params: params.value
  });
  if (!error) {
    list.value = data;
    scrollToBottom();
  }
  loading.value = false;
}
</script>

<template>
  <div class="h-full">
    <Teleport defer to="#header-extra">
      <div class="chat-history-header-filter">
        <NForm :model="params" label-placement="left" :show-feedback="false" inline>
          <NFormItem label="用户">
            <TheSelect
              v-model:value="userId"
              url="admin/users/list"
              :params="{ page: 1, size: 999 }"
              key-field="content"
              value-field="userId"
              label-field="username"
              class="clear w-200px!"
              placeholder="全部用户"
            />
          </NFormItem>
          <NFormItem label="时间">
            <NDatePicker v-model:value="range" type="daterange" class="clear" clearable />
          </NFormItem>
        </NForm>
      </div>
    </Teleport>
    <NScrollbar ref="scrollbarRef">
      <NSpin :show="loading" class="h-full">
        <VueMarkdownItProvider>
          <ChatMessage v-for="(item, index) in list" :key="index" :msg="item" />
        </VueMarkdownItProvider>
        <NEmpty v-if="!list.length" description="暂无数据" class="mt-60" />
      </NSpin>
    </NScrollbar>
  </div>
</template>

<style scoped lang="scss">
.chat-history-header-filter {
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

.chat-history-header-filter :deep(.n-form-item) {
  margin-bottom: 0;
}
</style>
