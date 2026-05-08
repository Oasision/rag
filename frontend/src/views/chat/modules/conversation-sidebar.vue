<script setup lang="ts">
defineOptions({
  name: 'ConversationSidebar'
});

const chatStore = useChatStore();
const { activeTab, conversationId, filteredSessions, sessionsLoading } = storeToRefs(chatStore);

const DEFAULT_WIDTH = 240;
const MIN_WIDTH = 180;
const MAX_WIDTH = 380;
const COLLAPSE_THRESHOLD = 156;

const collapsed = ref(false);
const sidebarRef = ref<HTMLElement | null>(null);
const sidebarWidth = ref(DEFAULT_WIDTH);
const isResizing = ref(false);

const sidebarStyle = computed(() => {
  if (collapsed.value) {
    return {};
  }

  return {
    width: `${sidebarWidth.value}px`,
    minWidth: `${sidebarWidth.value}px`
  };
});

onMounted(() => {
  chatStore.loadSessions();
});

function formatDate(dateStr?: string) {
  if (!dateStr) {
    return '';
  }

  const date = dayjs(dateStr);
  const now = dayjs();
  if (date.isSame(now, 'day')) {
    return date.format('HH:mm');
  }
  if (date.isSame(now, 'year')) {
    return date.format('MM-DD');
  }
  return date.format('YYYY-MM-DD');
}

function expandSidebar() {
  collapsed.value = false;
  sidebarWidth.value = Math.max(sidebarWidth.value, DEFAULT_WIDTH);
}

function collapseSidebar() {
  collapsed.value = true;
  stopResize();
}

function startResize(event: PointerEvent) {
  if (collapsed.value) {
    return;
  }

  isResizing.value = true;
  window.addEventListener('pointermove', handleResize);
  window.addEventListener('pointerup', stopResize);
  event.preventDefault();
}

function handleResize(event: PointerEvent) {
  if (!isResizing.value || !sidebarRef.value) {
    return;
  }

  const sidebarLeft = sidebarRef.value.getBoundingClientRect().left;
  const nextWidth = event.clientX - sidebarLeft;

  if (nextWidth < COLLAPSE_THRESHOLD) {
    collapseSidebar();
    return;
  }

  sidebarWidth.value = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, nextWidth));
}

function stopResize() {
  isResizing.value = false;
  window.removeEventListener('pointermove', handleResize);
  window.removeEventListener('pointerup', stopResize);
}

onBeforeUnmount(() => {
  stopResize();
});
</script>

<template>
  <aside
    ref="sidebarRef"
    class="conversation-sidebar"
    :class="{ collapsed, resizing: isResizing }"
    :style="sidebarStyle"
  >
    <div v-if="collapsed" class="collapsed-actions">
      <NButton type="primary" size="small" circle @click="chatStore.createNewSession">
        <template #icon>
          <icon-material-symbols:add-rounded />
        </template>
      </NButton>
      <NButton text size="small" @click="expandSidebar">
        <template #icon>
          <icon-material-symbols:chevron-right-rounded />
        </template>
      </NButton>
    </div>

    <template v-else>
      <header class="sidebar-header">
        <span class="title">对话</span>
        <div class="header-actions">
          <NButton type="primary" secondary size="small" @click="chatStore.createNewSession">
            <template #icon>
              <icon-material-symbols:add-rounded />
            </template>
            新建
          </NButton>
        </div>
      </header>

      <div class="tabs" role="tablist">
        <button :class="{ active: activeTab === 'active' }" type="button" @click="activeTab = 'active'">进行中</button>
        <button :class="{ active: activeTab === 'archived' }" type="button" @click="activeTab = 'archived'">
          已归档
        </button>
      </div>

      <NSpin :show="sessionsLoading" class="session-spin">
        <div v-if="filteredSessions.length === 0" class="empty-state">
          <icon-material-symbols:chat-outline-rounded />
          <span>暂无对话</span>
        </div>

        <div v-else class="session-list">
          <button
            v-for="session in filteredSessions"
            :key="session.conversationId"
            type="button"
            class="session-item"
            :class="{ selected: session.conversationId === conversationId }"
            @click="chatStore.switchSession(session.conversationId)"
          >
            <span class="session-icon">
              <icon-material-symbols:chat-outline-rounded />
            </span>
            <span class="session-main">
              <span class="session-title">{{ session.title || '新对话' }}</span>
              <span class="session-time">{{ formatDate(session.updatedAt) }}</span>
            </span>

            <NPopconfirm
              v-if="activeTab === 'active'"
              @positive-click="chatStore.archiveSession(session.conversationId)"
            >
              <template #trigger>
                <NButton text size="tiny" class="session-action" @click.stop>
                  <template #icon>
                    <icon-material-symbols:archive-outline-rounded />
                  </template>
                </NButton>
              </template>
              确认归档这个对话？
            </NPopconfirm>

            <NButton
              v-else
              text
              size="tiny"
              class="session-action"
              @click.stop="chatStore.unarchiveSession(session.conversationId)"
            >
              <template #icon>
                <icon-material-symbols:unarchive-outline-rounded />
              </template>
            </NButton>
          </button>
        </div>
      </NSpin>

      <div
        class="resize-rail"
        role="separator"
        aria-orientation="vertical"
        title="拖动调整宽度"
        @pointerdown="startResize"
      >
        <NButton text size="small" class="edge-collapse-button" @pointerdown.stop @click.stop="collapseSidebar">
          <template #icon>
            <icon-material-symbols:chevron-left-rounded />
          </template>
        </NButton>
      </div>
    </template>
  </aside>
</template>

<style scoped>
.conversation-sidebar {
  position: relative;
  display: flex;
  width: 240px;
  min-width: 240px;
  height: 100%;
  flex-direction: column;
  background: rgb(255 255 255 / 0.92);
  transition:
    width 0.2s ease,
    min-width 0.2s ease;
  user-select: none;
}

.conversation-sidebar.collapsed {
  width: 48px;
  min-width: 48px;
  align-items: center;
}

.conversation-sidebar.resizing {
  transition: none;
}

.collapsed-actions {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-top: 14px;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 34px 10px 14px;
}

.title {
  font-size: 15px;
  font-weight: 600;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  margin: 0 34px 10px 12px;
  border-radius: 8px;
  background: rgb(15 23 42 / 0.06);
  padding: 4px;
}

.tabs button {
  height: 30px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: rgb(71 85 105);
  cursor: pointer;
  font-size: 13px;
}

.tabs button.active {
  background: #fff;
  color: rgb(var(--primary-color));
  box-shadow: 0 1px 4px rgb(15 23 42 / 0.12);
}

.session-spin,
.session-list {
  min-height: 0;
  flex: 1;
}

.session-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow-y: auto;
  padding: 0 28px 12px 8px;
}

.session-item {
  display: flex;
  min-height: 56px;
  width: 100%;
  align-items: center;
  gap: 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  padding: 8px 8px;
  text-align: left;
}

.session-item:hover,
.session-item.selected {
  background: rgb(var(--primary-color) / 0.08);
}

.session-icon {
  display: flex;
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: rgb(15 23 42 / 0.06);
}

.session-main {
  min-width: 0;
  flex: 1;
}

.session-title,
.session-time {
  display: block;
}

.session-title {
  overflow: hidden;
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-time {
  margin-top: 2px;
  color: rgb(100 116 139);
  font-size: 11px;
}

.session-action {
  flex: 0 0 auto;
  opacity: 0;
}

.session-item:hover .session-action,
.session-item.selected .session-action {
  opacity: 1;
}

.empty-state {
  display: flex;
  height: 160px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: rgb(148 163 184);
  font-size: 13px;
}

.empty-state svg {
  font-size: 32px;
}

.resize-rail {
  position: absolute;
  top: 0;
  right: 0;
  z-index: 2;
  width: 24px;
  height: 100%;
  cursor: col-resize;
}

.resize-rail::before {
  position: absolute;
  top: 0;
  right: 0;
  width: 1px;
  height: 100%;
  background: rgb(15 23 42 / 0.08);
  content: '';
  transition: background 0.15s ease;
}

.resize-rail:hover::before,
.conversation-sidebar.resizing .resize-rail::before {
  background: rgb(var(--primary-color) / 0.48);
}

.edge-collapse-button {
  position: absolute;
  top: 39px;
  right: 1px;
  cursor: pointer;
}
</style>
