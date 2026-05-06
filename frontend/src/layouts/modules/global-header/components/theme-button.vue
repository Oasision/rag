<script setup lang="ts">
import type { PopoverPlacement } from 'naive-ui';
import { useAppStore } from '@/store/modules/app';
import { $t } from '@/locales';

defineOptions({
  name: 'ThemeButton'
});

interface Props {
  showTooltip?: boolean;
  tooltipPlacement?: PopoverPlacement;
}

withDefaults(defineProps<Props>(), {
  showTooltip: true,
  tooltipPlacement: 'top'
});

const appStore = useAppStore();
</script>

<template>
  <ButtonIcon
    icon="majesticons:color-swatch-line"
    :aria-label="$t('icon.themeConfig')"
    :tooltip-content="showTooltip ? $t('icon.themeConfig') : ''"
    :tooltip-placement="tooltipPlacement"
    @click="appStore.openThemeDrawer"
  />
</template>

<style scoped></style>
