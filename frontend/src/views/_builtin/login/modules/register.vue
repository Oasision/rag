<script setup lang="ts">
import { normalizeBackendMessage } from '@/service/request/shared';
import { $t } from '@/locales';

defineOptions({
  name: 'Register'
});

const route = useRoute();
const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();

const inviteCodeErrorCodes = new Set([
  'INVITE_CODE_REQUIRED',
  'INVITE_CODE_INVALID',
  'INVITE_CODE_EXPIRED',
  'INVITE_CODE_EXHAUSTED'
]);

interface FormModel {
  username: string;
  password: string;
  confirmPassword: string;
  inviteCode: string;
}

const model: FormModel = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  inviteCode: ''
});

const rules = computed<Record<keyof FormModel, App.Global.FormRule[]>>(() => {
  const { formRules, defaultRequiredRule, createConfirmPwdRule } = useFormRules();

  return {
    username: formRules.userName,
    password: formRules.pwd,
    confirmPassword: createConfirmPwdRule(model.password),
    inviteCode: [defaultRequiredRule]
  };
});

const loading = ref(false);
const inviteCodeErrorMessage = ref('');

function clearInviteCodeError() {
  inviteCodeErrorMessage.value = '';
}

function resolveRegisterError(error: any) {
  const rawMessage = String(error?.response?.data?.message || error?.message || '');
  const message = normalizeBackendMessage(rawMessage || '注册失败，请稍后重试');

  return {
    rawMessage,
    message
  };
}

async function handleSubmit() {
  clearInviteCodeError();
  await validate();
  loading.value = true;
  const { error } = await fetchRegister(model.username, model.password, model.inviteCode.trim());
  if (!error) {
    window.$message?.success('注册成功');
    toggleLoginModule('pwd-login');
  } else {
    const { rawMessage, message } = resolveRegisterError(error);

    if (inviteCodeErrorCodes.has(rawMessage)) {
      inviteCodeErrorMessage.value = message;
    }
  }
  loading.value = false;
}

function syncInviteCodeFromQuery(inviteCode: unknown) {
  if (typeof inviteCode !== 'string') return;
  model.inviteCode = inviteCode.trim();
}

watch(
  () => route.query.inviteCode,
  inviteCode => {
    syncInviteCodeFromQuery(inviteCode);
  },
  { immediate: true }
);

watch(
  () => model.inviteCode,
  () => {
    if (inviteCodeErrorMessage.value) {
      clearInviteCodeError();
    }
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
    class="register-form-panel"
    @keyup.enter="handleSubmit"
  >
    <NFormItem path="username">
      <NInput v-model:value="model.username" :placeholder="$t('page.login.common.userNamePlaceholder')">
        <template #prefix>
          <icon-ant-design:user-outlined />
        </template>
      </NInput>
    </NFormItem>
    <NFormItem path="password">
      <NInput
        v-model:value="model.password"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.passwordPlaceholder')"
      >
        <template #prefix>
          <icon-ant-design:key-outlined />
        </template>
      </NInput>
    </NFormItem>
    <NFormItem path="confirmPassword">
      <NInput
        v-model:value="model.confirmPassword"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.confirmPasswordPlaceholder')"
      >
        <template #prefix>
          <icon-ant-design:key-outlined />
        </template>
      </NInput>
    </NFormItem>
    <NFormItem
      path="inviteCode"
      :validation-status="inviteCodeErrorMessage ? 'error' : undefined"
      :feedback="inviteCodeErrorMessage || undefined"
    >
      <NInput v-model:value="model.inviteCode" :placeholder="$t('page.login.common.inviteCodePlaceholder')">
        <template #prefix>
          <icon-ant-design:safety-certificate-outlined />
        </template>
      </NInput>
    </NFormItem>
    <NSpace vertical :size="18" class="w-full">
      <NButton type="primary" size="large" round block :loading="loading" @click="handleSubmit">
        {{ $t('page.login.common.register') }}
      </NButton>
      <NButton block @click="toggleLoginModule('pwd-login')">
        {{ $t('page.login.common.back') }}
      </NButton>
    </NSpace>

    <div class="mt-4 text-center">
      {{ $t('page.login.register.agreement') }}
      <NButton text type="primary">{{ $t('page.login.register.protocol') }}</NButton>
      {{ $t('page.login.register.and') }}
      <NButton text type="primary">{{ $t('page.login.register.policy') }}</NButton>
    </div>
  </NForm>
</template>

<style scoped>
.register-form-panel {
  min-width: 0;
}
</style>
