<script setup lang="ts">
import { computed } from 'vue';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import ThemeButton from '../../global-header/components/theme-button.vue';
import UserAvatar from '../../global-header/components/user-avatar.vue';

defineOptions({
  name: 'SiderActions'
});

const appStore = useAppStore();
const themeStore = useThemeStore();

const compact = computed(() => appStore.siderCollapse);
const isDev = import.meta.env.DEV;
</script>

<template>
  <div class="sider-actions" :class="{ 'sider-actions--compact': compact }">
    <div class="sider-actions__panel bg-container shadow-2xl">
      <div class="sider-actions__tools">
        <LangSwitch
          v-if="themeStore.header.multilingual.visible"
          :lang="appStore.locale"
          :lang-options="appStore.localeOptions"
          :show-tooltip="true"
          @change-lang="appStore.changeLocale"
        />
        <NTooltip placement="top" :delay="0">
          <template #trigger>
            <div class="sider-actions__trigger">
              <ThemeSchemaSwitch
                :theme-schema="themeStore.themeScheme"
                :show-tooltip="false"
                @switch="themeStore.toggleThemeScheme"
              />
            </div>
          </template>
          {{ $t('icon.themeSchema') }}
        </NTooltip>
        <NTooltip v-if="isDev" placement="top" :delay="0">
          <template #trigger>
            <div class="sider-actions__trigger">
              <ThemeButton :show-tooltip="false" />
            </div>
          </template>
          {{ $t('icon.themeConfig') }}
        </NTooltip>
      </div>
      <div class="sider-actions__account">
        <UserAvatar :compact="compact" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.sider-actions {
  padding: 2px 12px 14px;
}

.sider-actions__panel {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid rgb(255 255 255 / 0.68);
  border-radius: 8px;
  background: linear-gradient(180deg, rgb(255 255 255 / 0.78) 0%, rgb(239 246 255 / 0.56) 100%);
  box-shadow:
    0 14px 34px rgb(15 23 42 / 0.1),
    0 0 0 1px rgb(255 255 255 / 0.42) inset;
  backdrop-filter: blur(16px) saturate(1.18);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    transform 0.2s ease;
}

.sider-actions__panel:hover {
  border-color: rgb(96 165 250 / 0.38);
  box-shadow:
    0 18px 42px rgb(37 99 235 / 0.13),
    0 0 0 1px rgb(255 255 255 / 0.52) inset;
  transform: translateY(-1px);
}

.sider-actions__tools {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
}

.sider-actions__account {
  display: flex;
  justify-content: center;
}

.sider-actions__trigger {
  display: flex;
  align-items: center;
  justify-content: center;
}

.sider-actions--compact {
  display: flex;
  justify-content: center;
}

.sider-actions--compact .sider-actions__panel {
  justify-content: center;
  padding: 10px 6px;
}

.sider-actions--compact .sider-actions__tools {
  flex-direction: column;
}
</style>
