<script setup lang="ts">
import { $t } from '@/locales';

defineOptions({
  name: 'GlobalLogo'
});

interface Props {
  /** Whether to show the title */
  showTitle?: boolean;
}

withDefaults(defineProps<Props>(), {
  showTitle: true
});
</script>

<template>
  <RouterLink to="/" class="global-logo-link w-full flex-center nowrap-hidden">
    <SystemLogo class="text-32px text-primary" />
    <h2 v-show="showTitle" class="pl-8px text-16px text-primary font-bold transition duration-300 ease-in-out">
      {{ $t('system.title') }}
    </h2>
  </RouterLink>
</template>

<style scoped>
.global-logo-link {
  gap: 2px;
  color: #1d4ed8;
}

.global-logo-link :deep(svg) {
  filter: drop-shadow(0 8px 18px rgb(37 99 235 / 0.18));
}

.global-logo-link h2 {
  letter-spacing: 0;
}
</style>
