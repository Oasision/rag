<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import ChatList from './modules/chat-list.vue';
import ConversationSidebar from './modules/conversation-sidebar.vue';
import InputBox from './modules/input-box.vue';
import ReferencePreviewPage from './modules/reference-preview-page.vue';

const route = useRoute();
const showReferencePreview = computed(() => route.query.preview === 'reference');
</script>

<template>
  <div v-if="showReferencePreview">
    <ReferencePreviewPage />
  </div>
  <div v-else class="chat-shell">
    <ConversationSidebar />
    <main class="chat-main">
      <ChatList />
      <InputBox />
    </main>
  </div>
</template>

<style scoped>
.chat-shell {
  display: flex;
  height: 100%;
  min-height: 0;
}

.chat-main {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 16px;
  padding-left: 16px;
}
</style>
