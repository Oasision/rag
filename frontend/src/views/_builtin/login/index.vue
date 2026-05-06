<script setup lang="ts">
import { computed } from 'vue';
import type { Component } from 'vue';
import { loginModuleRecord } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { $t } from '@/locales';
import LoginMascotScene from './components/login-mascot-scene.vue';
import PwdLogin from './modules/pwd-login.vue';
import CodeLogin from './modules/code-login.vue';
import Register from './modules/register.vue';
import ResetPwd from './modules/reset-pwd.vue';
import BindWechat from './modules/bind-wechat.vue';
import { provideLoginScene } from './shared';

interface Props {
  /** The login module */
  module?: UnionKey.LoginModule;
}

interface LoginModule {
  label: string;
  component: Component;
}

const props = defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();

provideLoginScene();

const moduleMap: Record<UnionKey.LoginModule, LoginModule> = {
  'pwd-login': { label: loginModuleRecord['pwd-login'], component: PwdLogin },
  'code-login': { label: loginModuleRecord['code-login'], component: CodeLogin },
  register: { label: loginModuleRecord.register, component: Register },
  'reset-pwd': { label: loginModuleRecord['reset-pwd'], component: ResetPwd },
  'bind-wechat': { label: loginModuleRecord['bind-wechat'], component: BindWechat }
};

const activeModule = computed(() => moduleMap[props.module || 'pwd-login']);
const isRegisterModule = computed(() => (props.module || 'pwd-login') === 'register');
</script>

<template>
  <div class="login-page">
    <div class="login-shell">
      <section class="login-shell__hero">
        <LoginMascotScene />
      </section>

      <section class="login-shell__form">
        <div class="login-toolbar">
          <div class="login-toolbar__spacer"></div>
          <div class="login-toolbar__actions">
            <LangSwitch
              v-if="themeStore.header.multilingual.visible"
              :lang="appStore.locale"
              :lang-options="appStore.localeOptions"
              :show-tooltip="false"
              @change-lang="appStore.changeLocale"
            />
          </div>
        </div>

        <NCard :bordered="false" class="login-card" :class="{ 'login-card--register': isRegisterModule }">
          <div class="login-card__header">
            <div class="login-card__brand">
              <div class="login-card__logo">
                <SystemLogo class="text-30px text-primary" />
              </div>
              <div>
                <div class="login-card__brand-title">{{ $t('page.login.brandTitle') }}</div>
                <div class="login-card__brand-subtitle">企业级知识工作台</div>
              </div>
            </div>

            <div class="login-card__title-group">
              <h2 class="login-card__title">{{ $t(activeModule.label) }}</h2>
            </div>
          </div>

          <main class="login-card__main">
            <Transition :name="themeStore.page.animateMode" mode="out-in" appear>
              <component :is="activeModule.component" />
            </Transition>
          </main>
        </NCard>
      </section>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(circle at top left, rgb(var(--primary-color) / 0.14), transparent 24%),
    linear-gradient(180deg, #f7f4fb 0%, #f2eff8 100%);
}

.login-shell {
  display: grid;
  width: min(1320px, 100%);
  grid-template-columns: minmax(0, 1.08fr) minmax(420px, 0.92fr);
  gap: 28px;
}

.login-shell__hero {
  min-width: 0;
}

.login-shell__form {
  position: relative;
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.login-toolbar {
  position: absolute;
  top: 6px;
  right: 4px;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 0;
}

.login-toolbar__spacer {
  flex: 1;
}

.login-toolbar__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.login-card {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  align-self: stretch;
  width: min(100%, 460px);
  border-radius: 32px;
  background: rgb(255 255 255 / 0.82);
  box-shadow:
    0 24px 80px rgb(41 28 66 / 0.12),
    inset 0 1px 0 rgb(255 255 255 / 0.8);
  backdrop-filter: blur(18px);
}

.login-card--register {
  width: min(100%, 460px);
}

.login-card__header {
  display: flex;
  flex-direction: column;
  gap: 26px;
}

.login-card__brand {
  display: flex;
  align-items: center;
  gap: 14px;
}

.login-card__logo {
  display: flex;
  width: 54px;
  height: 54px;
  align-items: center;
  justify-content: center;
  border-radius: 18px;
  background: linear-gradient(145deg, rgb(var(--primary-color) / 0.16), rgb(var(--primary-color) / 0.06));
}

.login-card__brand-title {
  font-size: 18px;
  font-weight: 700;
  color: rgb(44 34 61 / 0.96);
}

.login-card__brand-subtitle {
  margin-top: 4px;
  font-size: 13px;
  color: rgb(88 75 108 / 0.7);
}

.login-card__title-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.login-card__title {
  font-size: 30px;
  font-weight: 700;
  line-height: 1.08;
  letter-spacing: -0.04em;
  color: rgb(36 28 51 / 0.98);
}

.login-card__main {
  padding-top: 28px;
}

@media (max-width: 1180px) {
  .login-shell {
    grid-template-columns: 1fr;
  }

  .login-shell__hero {
    min-height: 540px;
  }

  .login-card,
  .login-card--register {
    width: min(100%, 460px);
  }
}

@media (max-width: 720px) {
  .login-page {
    padding: 16px;
  }

  .login-shell {
    gap: 20px;
  }

  .login-shell__hero {
    display: none;
  }

  .login-toolbar {
    justify-content: flex-end;
    top: 0;
    right: 0;
  }

  .login-card,
  .login-card--register {
    width: 100%;
    border-radius: 24px;
  }

  .login-card__title {
    font-size: 26px;
  }
}
</style>
