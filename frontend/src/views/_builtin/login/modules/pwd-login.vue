<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { loginModuleRecord } from '@/constants/app';
import { useAuthStore } from '@/store/modules/auth';
import { useRouterPush } from '@/hooks/common/router';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { localStg } from '@/utils/storage';
import { $t } from '@/locales';
import { useLoginScene } from '../shared';

defineOptions({
  name: 'PwdLogin'
});

interface FormModel {
  userName: string;
  password: string;
  rememberMe: boolean;
}

type FormRuleModel = Pick<FormModel, 'userName' | 'password'>;

const authStore = useAuthStore();
const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();
const loginScene = useLoginScene();
const passwordVisible = ref(false);

const rememberedLogin = localStg.get('rememberedLogin');

const model: FormModel = reactive({
  userName: rememberedLogin?.userName || '',
  password: rememberedLogin?.password || '',
  rememberMe: Boolean(rememberedLogin)
});

const rules = computed<Record<keyof FormRuleModel, App.Global.FormRule[]>>(() => {
  const { formRules } = useFormRules();

  return {
    userName: formRules.userName,
    password: formRules.pwd
  };
});

async function handleSubmit() {
  try {
    await validate();
  } catch {
    loginScene.triggerLoginError();
    return;
  }

  const success = await authStore.login(model.userName, model.password);

  if (!success) {
    loginScene.triggerLoginError();
    return;
  }

  if (model.rememberMe) {
    localStg.set('rememberedLogin', {
      userName: model.userName,
      password: model.password
    });
  } else {
    localStg.remove('rememberedLogin');
  }
}

function togglePasswordVisible() {
  passwordVisible.value = !passwordVisible.value;
  loginScene.setPasswordVisible(passwordVisible.value);
}

onMounted(() => {
  loginScene.userName.value = model.userName;
  loginScene.password.value = model.password;
  loginScene.setPasswordVisible(passwordVisible.value);
});

onBeforeUnmount(() => {
  loginScene.reset(true);
});

watch(
  () => model.userName,
  value => {
    loginScene.setUserName(value);
  }
);

watch(
  () => model.password,
  value => {
    loginScene.setPassword(value);
  }
);
</script>

<template>
  <NForm
    ref="formRef"
    :model="model"
    :rules="rules"
    size="large"
    :show-label="false"
    class="login-form"
    @keyup.enter="handleSubmit"
  >
    <NFormItem path="userName">
      <NInput
        v-model:value="model.userName"
        :placeholder="$t('page.login.common.userNamePlaceholder')"
        @focus="loginScene.setFieldFocus('userName')"
        @blur="loginScene.clearFieldFocus('userName')"
      >
        <template #prefix>
          <icon-ant-design:user-outlined />
        </template>
      </NInput>
    </NFormItem>
    <NFormItem path="password">
      <NInput
        v-model:value="model.password"
        :type="passwordVisible ? 'text' : 'password'"
        :placeholder="$t('page.login.common.passwordPlaceholder')"
        @focus="loginScene.setFieldFocus('password')"
        @blur="loginScene.clearFieldFocus('password')"
      >
        <template #prefix>
          <icon-ant-design:key-outlined />
        </template>
        <template #suffix>
          <button type="button" class="password-toggle" @mousedown.prevent @click="togglePasswordVisible">
            <icon-ant-design:eye-outlined v-if="passwordVisible" />
            <icon-ant-design:eye-invisible-outlined v-else />
          </button>
        </template>
      </NInput>
    </NFormItem>
    <div class="mb-6 flex-y-center justify-between">
      <NCheckbox v-model:checked="model.rememberMe">
        {{ $t('page.login.pwdLogin.rememberMe') }}
      </NCheckbox>
    </div>
    <div class="login-actions">
      <NButton
        type="primary"
        size="large"
        round
        block
        class="login-submit"
        :loading="authStore.loginLoading"
        @click="handleSubmit"
      >
        {{ $t('page.login.common.login') }}
      </NButton>
      <NButton block class="login-secondary" @click="toggleLoginModule('register')">
        {{ $t(loginModuleRecord.register) }}
      </NButton>

      <span class="login-agreement">
        登录即代表已阅读并同意我们的
        <NButton text type="primary">用户协议</NButton>
        和
        <NButton text type="primary">隐私政策</NButton>
      </span>
    </div>
  </NForm>
</template>

<style scoped>
.login-form {
  display: flex;
  flex-direction: column;
}

.password-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  padding: 0;
  font-size: 18px;
  color: rgb(var(--text-color-3));
  background: transparent;
  cursor: pointer;
}

.password-toggle:hover {
  color: rgb(var(--primary-color));
}

.login-actions {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.login-submit {
  box-shadow: 0 14px 28px rgb(var(--primary-color) / 0.18);
}

.login-secondary {
  border-color: rgb(210 214 224 / 0.9);
  background: rgb(246 247 251 / 0.88);
}

.login-agreement {
  text-align: center;
}
</style>
