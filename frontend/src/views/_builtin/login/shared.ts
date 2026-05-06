import { inject, provide, ref } from 'vue';
import type { InjectionKey, Ref } from 'vue';

type ActiveField = 'userName' | 'password' | null;

export interface LoginSceneContext {
  userName: Ref<string>;
  password: Ref<string>;
  activeField: Ref<ActiveField>;
  isUserTyping: Ref<boolean>;
  isPasswordVisible: Ref<boolean>;
  isLoginError: Ref<boolean>;
  isShaking: Ref<boolean>;
  setUserName: (value: string) => void;
  setPassword: (value: string) => void;
  setFieldFocus: (field: Exclude<ActiveField, null>) => void;
  clearFieldFocus: (field: Exclude<ActiveField, null>) => void;
  setPasswordVisible: (visible: boolean) => void;
  triggerLoginError: () => void;
  reset: (clearCredentials?: boolean) => void;
}

const loginSceneKey: InjectionKey<LoginSceneContext> = Symbol('login-scene');

function createLoginScene(): LoginSceneContext {
  const userName = ref('');
  const password = ref('');
  const activeField = ref<ActiveField>(null);
  const isUserTyping = ref(false);
  const isPasswordVisible = ref(false);
  const isLoginError = ref(false);
  const isShaking = ref(false);

  let typingTimer: ReturnType<typeof setTimeout> | null = null;
  let shakeTimer: ReturnType<typeof setTimeout> | null = null;
  let recoverTimer: ReturnType<typeof setTimeout> | null = null;

  function clearTypingTimer() {
    if (typingTimer) {
      clearTimeout(typingTimer);
      typingTimer = null;
    }
  }

  function clearErrorTimers() {
    if (shakeTimer) {
      clearTimeout(shakeTimer);
      shakeTimer = null;
    }

    if (recoverTimer) {
      clearTimeout(recoverTimer);
      recoverTimer = null;
    }
  }

  function pulseTyping() {
    isUserTyping.value = true;
    clearTypingTimer();

    typingTimer = setTimeout(() => {
      isUserTyping.value = false;
      typingTimer = null;
    }, 800);
  }

  function setUserName(value: string) {
    userName.value = value;
    pulseTyping();
  }

  function setPassword(value: string) {
    password.value = value;
  }

  function setFieldFocus(field: Exclude<ActiveField, null>) {
    activeField.value = field;

    if (field === 'userName') {
      pulseTyping();
    }
  }

  function clearFieldFocus(field: Exclude<ActiveField, null>) {
    if (activeField.value === field) {
      activeField.value = null;
    }

    if (field === 'userName') {
      clearTypingTimer();
      isUserTyping.value = false;
    }
  }

  function setPasswordVisible(visible: boolean) {
    isPasswordVisible.value = visible;
  }

  function triggerLoginError() {
    clearErrorTimers();
    isLoginError.value = true;
    isShaking.value = false;

    shakeTimer = setTimeout(() => {
      isShaking.value = true;
      shakeTimer = null;
    }, 350);

    recoverTimer = setTimeout(() => {
      isLoginError.value = false;
      isShaking.value = false;
      recoverTimer = null;
    }, 2500);
  }

  function reset(clearCredentials = false) {
    clearTypingTimer();
    clearErrorTimers();
    activeField.value = null;
    isUserTyping.value = false;
    isPasswordVisible.value = false;
    isLoginError.value = false;
    isShaking.value = false;

    if (clearCredentials) {
      userName.value = '';
      password.value = '';
    }
  }

  return {
    userName,
    password,
    activeField,
    isUserTyping,
    isPasswordVisible,
    isLoginError,
    isShaking,
    setUserName,
    setPassword,
    setFieldFocus,
    clearFieldFocus,
    setPasswordVisible,
    triggerLoginError,
    reset
  };
}

export function provideLoginScene() {
  const scene = createLoginScene();
  provide(loginSceneKey, scene);
  return scene;
}

export function useLoginScene() {
  const scene = inject(loginSceneKey, null);

  if (!scene) {
    throw new Error('Login scene context is not available.');
  }

  return scene;
}
