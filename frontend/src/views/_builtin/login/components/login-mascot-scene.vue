<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { $t } from '@/locales';
import { useLoginScene } from '../shared';

interface CharacterRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

const loginScene = useLoginScene();
const sceneRef = ref<HTMLElement | null>(null);

const pointer = ref({ x: 240, y: 180 });
const isPurpleBlinking = ref(false);
const isBlackBlinking = ref(false);
const isPurplePeeking = ref(false);

let purpleBlinkTimer: ReturnType<typeof setTimeout> | null = null;
let blackBlinkTimer: ReturnType<typeof setTimeout> | null = null;
let purplePeekTimer: ReturnType<typeof setTimeout> | null = null;
let purplePeekRecoverTimer: ReturnType<typeof setTimeout> | null = null;

const purpleRect: CharacterRect = { left: 60, top: 0, width: 170, height: 370 };
const blackRect: CharacterRect = { left: 220, top: 70, width: 115, height: 290 };
const orangeRect: CharacterRect = { left: 0, top: 170, width: 230, height: 190 };
const yellowRect: CharacterRect = { left: 290, top: 145, width: 135, height: 215 };

const isShowingPassword = computed(() => Boolean(loginScene.password.value) && loginScene.isPasswordVisible.value);
const isLookingAway = computed(
  () => loginScene.activeField.value === 'password' && !loginScene.isPasswordVisible.value
);
const isLookingAtEachOther = computed(
  () => loginScene.activeField.value === 'userName' || loginScene.isUserTyping.value
);

function updatePointerPosition(event: MouseEvent) {
  if (!sceneRef.value) return;

  const rect = sceneRef.value.getBoundingClientRect();
  pointer.value = {
    x: event.clientX - rect.left,
    y: event.clientY - rect.top
  };
}

function calcMotion(rect: CharacterRect) {
  const centerX = rect.left + rect.width / 2;
  const centerY = rect.top + rect.height / 3;
  const dx = pointer.value.x - centerX;
  const dy = pointer.value.y - centerY;

  return {
    faceX: Math.max(-15, Math.min(15, dx / 20)),
    faceY: Math.max(-10, Math.min(10, dy / 30)),
    bodySkew: Math.max(-6, Math.min(6, -dx / 120))
  };
}

function calcPupilOffset(centerX: number, centerY: number, maxDistance: number) {
  const dx = pointer.value.x - centerX;
  const dy = pointer.value.y - centerY;
  const distance = Math.min(Math.sqrt(dx * dx + dy * dy), maxDistance);
  const angle = Math.atan2(dy, dx);

  return {
    x: Math.cos(angle) * distance,
    y: Math.sin(angle) * distance
  };
}

function clearPeekTimers() {
  if (purplePeekTimer) {
    clearTimeout(purplePeekTimer);
    purplePeekTimer = null;
  }

  if (purplePeekRecoverTimer) {
    clearTimeout(purplePeekRecoverTimer);
    purplePeekRecoverTimer = null;
  }
}

function clearBlinkTimers() {
  if (purpleBlinkTimer) {
    clearTimeout(purpleBlinkTimer);
    purpleBlinkTimer = null;
  }

  if (blackBlinkTimer) {
    clearTimeout(blackBlinkTimer);
    blackBlinkTimer = null;
  }
}

function schedulePurpleBlink() {
  purpleBlinkTimer = setTimeout(
    () => {
      isPurpleBlinking.value = true;

      setTimeout(() => {
        isPurpleBlinking.value = false;
        schedulePurpleBlink();
      }, 150);
    },
    Math.random() * 4000 + 3000
  );
}

function scheduleBlackBlink() {
  blackBlinkTimer = setTimeout(
    () => {
      isBlackBlinking.value = true;

      setTimeout(() => {
        isBlackBlinking.value = false;
        scheduleBlackBlink();
      }, 150);
    },
    Math.random() * 4000 + 3000
  );
}

function schedulePeek() {
  clearPeekTimers();

  if (!isShowingPassword.value) {
    isPurplePeeking.value = false;
    return;
  }

  purplePeekTimer = setTimeout(
    () => {
      if (!isShowingPassword.value) return;

      isPurplePeeking.value = true;

      purplePeekRecoverTimer = setTimeout(() => {
        isPurplePeeking.value = false;
        schedulePeek();
      }, 800);
    },
    Math.random() * 3000 + 2000
  );
}

const purpleMotion = computed(() => calcMotion(purpleRect));
const blackMotion = computed(() => calcMotion(blackRect));
const orangeMotion = computed(() => calcMotion(orangeRect));
const yellowMotion = computed(() => calcMotion(yellowRect));

const purpleBodyStyle = computed(() => {
  if (isShowingPassword.value) {
    return {
      transform: 'skewX(0deg)',
      height: '370px'
    };
  }

  if (isLookingAway.value) {
    return {
      transform: 'skewX(-14deg) translateX(-20px)',
      height: '410px'
    };
  }

  if (isLookingAtEachOther.value) {
    return {
      transform: `skewX(${purpleMotion.value.bodySkew - 12}deg) translateX(40px)`,
      height: '410px'
    };
  }

  return {
    transform: `skewX(${purpleMotion.value.bodySkew}deg)`,
    height: '370px'
  };
});

const purpleEyesStyle = computed(() => {
  if (loginScene.isLoginError.value) {
    return {
      left: '30px',
      top: '55px'
    };
  }

  if (isLookingAway.value) {
    return {
      left: '20px',
      top: '25px'
    };
  }

  if (isShowingPassword.value) {
    return {
      left: '20px',
      top: '35px'
    };
  }

  if (isLookingAtEachOther.value) {
    return {
      left: '55px',
      top: '65px'
    };
  }

  return {
    left: `${45 + purpleMotion.value.faceX}px`,
    top: `${40 + purpleMotion.value.faceY}px`
  };
});

const purplePupilStyle = computed(() => {
  if (loginScene.isLoginError.value) {
    return {
      transform: 'translate(-3px, 4px)'
    };
  }

  if (isLookingAway.value) {
    return {
      transform: 'translate(-5px, -5px)'
    };
  }

  if (isShowingPassword.value) {
    const x = isPurplePeeking.value ? 4 : -4;
    const y = isPurplePeeking.value ? 5 : -4;

    return {
      transform: `translate(${x}px, ${y}px)`
    };
  }

  if (isLookingAtEachOther.value) {
    return {
      transform: 'translate(3px, 4px)'
    };
  }

  const offset = calcPupilOffset(114, 49, 5);

  return {
    transform: `translate(${offset.x}px, ${offset.y}px)`
  };
});

const purpleEyeballStyle = computed(() => ({
  height: isPurpleBlinking.value ? '2px' : '18px'
}));

const blackBodyStyle = computed(() => {
  if (isShowingPassword.value) {
    return {
      transform: 'skewX(0deg)'
    };
  }

  if (isLookingAway.value) {
    return {
      transform: 'skewX(12deg) translateX(-10px)'
    };
  }

  if (isLookingAtEachOther.value) {
    return {
      transform: `skewX(${blackMotion.value.bodySkew * 1.5 + 10}deg) translateX(20px)`
    };
  }

  return {
    transform: `skewX(${blackMotion.value.bodySkew}deg)`
  };
});

const blackEyesStyle = computed(() => {
  if (loginScene.isLoginError.value) {
    return {
      left: '15px',
      top: '40px'
    };
  }

  if (isLookingAway.value) {
    return {
      left: '10px',
      top: '20px'
    };
  }

  if (isShowingPassword.value) {
    return {
      left: '10px',
      top: '28px'
    };
  }

  if (isLookingAtEachOther.value) {
    return {
      left: '32px',
      top: '12px'
    };
  }

  return {
    left: `${26 + blackMotion.value.faceX}px`,
    top: `${32 + blackMotion.value.faceY}px`
  };
});

const blackPupilStyle = computed(() => {
  if (loginScene.isLoginError.value) {
    return {
      transform: 'translate(-3px, 4px)'
    };
  }

  if (isLookingAway.value) {
    return {
      transform: 'translate(-4px, -5px)'
    };
  }

  if (isShowingPassword.value) {
    return {
      transform: 'translate(-4px, -4px)'
    };
  }

  if (isLookingAtEachOther.value) {
    return {
      transform: 'translate(0px, -4px)'
    };
  }

  const offset = calcPupilOffset(254, 110, 4);

  return {
    transform: `translate(${offset.x}px, ${offset.y}px)`
  };
});

const blackEyeballStyle = computed(() => ({
  height: isBlackBlinking.value ? '2px' : '16px'
}));

const orangeBodyStyle = computed(() => {
  if (isShowingPassword.value) {
    return {
      transform: 'skewX(0deg)'
    };
  }

  return {
    transform: `skewX(${orangeMotion.value.bodySkew}deg)`
  };
});

const orangeEyesStyle = computed(() => {
  if (loginScene.isLoginError.value) {
    return {
      left: '60px',
      top: '95px'
    };
  }

  if (isLookingAway.value) {
    return {
      left: '50px',
      top: '75px'
    };
  }

  if (isShowingPassword.value) {
    return {
      left: '50px',
      top: '85px'
    };
  }

  return {
    left: `${82 + orangeMotion.value.faceX}px`,
    top: `${90 + orangeMotion.value.faceY}px`
  };
});

const orangePupilStyle = computed(() => {
  if (loginScene.isLoginError.value) {
    return {
      transform: 'translate(-3px, 4px)'
    };
  }

  if (isLookingAway.value) {
    return {
      transform: 'translate(-5px, -5px)'
    };
  }

  if (isShowingPassword.value) {
    return {
      transform: 'translate(-5px, -4px)'
    };
  }

  const offset = calcPupilOffset(94, 266, 5);

  return {
    transform: `translate(${offset.x}px, ${offset.y}px)`
  };
});

const orangeMouthStyle = computed(() => {
  if (loginScene.isLoginError.value) {
    return {
      left: `${80 + orangeMotion.value.faceX}px`,
      top: '130px'
    };
  }

  return {
    left: '90px',
    top: '120px'
  };
});

const yellowBodyStyle = computed(() => {
  if (isShowingPassword.value) {
    return {
      transform: 'skewX(0deg)'
    };
  }

  return {
    transform: `skewX(${yellowMotion.value.bodySkew}deg)`
  };
});

const yellowEyesStyle = computed(() => {
  if (loginScene.isLoginError.value) {
    return {
      left: '35px',
      top: '45px'
    };
  }

  if (isLookingAway.value) {
    return {
      left: '20px',
      top: '30px'
    };
  }

  if (isShowingPassword.value) {
    return {
      left: '20px',
      top: '35px'
    };
  }

  return {
    left: `${52 + yellowMotion.value.faceX}px`,
    top: `${40 + yellowMotion.value.faceY}px`
  };
});

const yellowPupilStyle = computed(() => {
  if (loginScene.isLoginError.value) {
    return {
      transform: 'translate(-3px, 4px)'
    };
  }

  if (isLookingAway.value) {
    return {
      transform: 'translate(-5px, -5px)'
    };
  }

  if (isShowingPassword.value) {
    return {
      transform: 'translate(-5px, -4px)'
    };
  }

  const offset = calcPupilOffset(342, 190, 5);

  return {
    transform: `translate(${offset.x}px, ${offset.y}px)`
  };
});

const yellowMouthStyle = computed(() => {
  if (loginScene.isLoginError.value) {
    return {
      left: '30px',
      top: '92px',
      transform: 'rotate(-8deg)'
    };
  }

  if (isLookingAway.value) {
    return {
      left: '15px',
      top: '78px',
      transform: 'rotate(0deg)'
    };
  }

  if (isShowingPassword.value) {
    return {
      left: '10px',
      top: '88px',
      transform: 'rotate(0deg)'
    };
  }

  return {
    left: `${40 + yellowMotion.value.faceX}px`,
    top: `${88 + yellowMotion.value.faceY}px`,
    transform: 'rotate(0deg)'
  };
});

watch(isShowingPassword, visible => {
  if (visible) {
    schedulePeek();
  } else {
    clearPeekTimers();
    isPurplePeeking.value = false;
  }
});

onMounted(() => {
  window.addEventListener('mousemove', updatePointerPosition);
  schedulePurpleBlink();
  scheduleBlackBlink();
});

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', updatePointerPosition);
  clearBlinkTimers();
  clearPeekTimers();
});
</script>

<template>
  <div class="mascot-panel">
    <div class="hero-copy hero-copy--brand">
      <div class="brand-row brand-row--hero">
        <div class="brand-badge brand-badge--hero">
          <SystemLogo class="brand-logo brand-logo--hero" />
        </div>
        <div>
          <div class="brand-title brand-title--hero">{{ $t('page.login.brandTitle') }}</div>
        </div>
      </div>
    </div>

    <div class="characters-wrapper">
      <div ref="sceneRef" class="characters-scene">
        <div class="character char-purple" :style="purpleBodyStyle">
          <div class="eyes" :class="{ 'shake-head': loginScene.isShaking.value }" :style="purpleEyesStyle">
            <div class="eyeball" :style="purpleEyeballStyle">
              <div class="pupil" :style="purplePupilStyle"></div>
            </div>
            <div class="eyeball" :style="purpleEyeballStyle">
              <div class="pupil" :style="purplePupilStyle"></div>
            </div>
          </div>
        </div>

        <div class="character char-black" :style="blackBodyStyle">
          <div class="eyes" :class="{ 'shake-head': loginScene.isShaking.value }" :style="blackEyesStyle">
            <div class="eyeball eyeball--sm" :style="blackEyeballStyle">
              <div class="pupil pupil--sm" :style="blackPupilStyle"></div>
            </div>
            <div class="eyeball eyeball--sm" :style="blackEyeballStyle">
              <div class="pupil pupil--sm" :style="blackPupilStyle"></div>
            </div>
          </div>
        </div>

        <div class="character char-orange" :style="orangeBodyStyle">
          <div class="eyes eyes--bare" :class="{ 'shake-head': loginScene.isShaking.value }" :style="orangeEyesStyle">
            <div class="bare-pupil" :style="orangePupilStyle"></div>
            <div class="bare-pupil" :style="orangePupilStyle"></div>
          </div>
          <div
            class="orange-mouth"
            :class="{ visible: loginScene.isLoginError.value, 'shake-head': loginScene.isShaking.value }"
            :style="orangeMouthStyle"
          ></div>
        </div>

        <div class="character char-yellow" :style="yellowBodyStyle">
          <div class="eyes eyes--bare" :class="{ 'shake-head': loginScene.isShaking.value }" :style="yellowEyesStyle">
            <div class="bare-pupil" :style="yellowPupilStyle"></div>
            <div class="bare-pupil" :style="yellowPupilStyle"></div>
          </div>
          <div
            class="yellow-mouth"
            :class="{ 'shake-head': loginScene.isShaking.value }"
            :style="yellowMouthStyle"
          ></div>
        </div>
      </div>
    </div>

    <div class="panel-footer">
      <span>知识检索</span>
      <span>RAG 工作流</span>
      <span>AI 工作台</span>
    </div>
  </div>
</template>

<style scoped>
.mascot-panel {
  position: relative;
  display: flex;
  min-height: 100%;
  flex-direction: column;
  justify-content: space-between;
  overflow: hidden;
  border-radius: 36px;
  padding: 36px 40px 32px;
  background:
    radial-gradient(circle at 20% 18%, rgb(255 255 255 / 0.32), transparent 24%),
    radial-gradient(circle at 78% 28%, rgb(133 114 168 / 0.2), transparent 26%),
    linear-gradient(145deg, #d4d0dc 0%, #c8c4d0 46%, #bbb7c5 100%);
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 0.45),
    0 32px 80px rgb(52 43 69 / 0.12);
}

.mascot-panel::before,
.mascot-panel::after {
  position: absolute;
  border-radius: 999px;
  content: '';
  filter: blur(90px);
}

.mascot-panel::before {
  left: -4%;
  bottom: 8%;
  width: 280px;
  height: 280px;
  background: rgb(230 224 236 / 0.78);
}

.mascot-panel::after {
  top: 12%;
  right: -6%;
  width: 230px;
  height: 230px;
  background: rgb(162 146 190 / 0.26);
}

.brand-row,
.hero-copy,
.panel-footer {
  position: relative;
  z-index: 1;
}

.brand-row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.brand-row--hero {
  align-items: flex-start;
  gap: 18px;
}

.brand-badge {
  display: flex;
  width: 46px;
  height: 46px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 0.35);
  border-radius: 14px;
  background: rgb(255 255 255 / 0.2);
  backdrop-filter: blur(12px);
}

.brand-badge--hero {
  width: 64px;
  height: 64px;
  border-radius: 20px;
}

.brand-logo {
  font-size: 26px;
  color: #fff;
}

.brand-logo--hero {
  font-size: 36px;
}

.brand-title {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 700;
  color: #fff;
}

.brand-title--hero {
  margin-top: 0;
  font-size: clamp(32px, 3.1vw, 44px);
  line-height: 1.06;
  letter-spacing: -0.04em;
  color: #2f273b;
}

.hero-copy {
  max-width: 360px;
  margin-top: 18px;
}

.hero-copy--brand {
  max-width: 420px;
  margin-top: 30px;
}

.characters-wrapper {
  position: relative;
  z-index: 1;
  display: flex;
  min-height: 420px;
  align-items: flex-end;
  justify-content: center;
}

.characters-scene {
  position: relative;
  width: min(100%, 480px);
  height: 360px;
}

.character {
  position: absolute;
  bottom: 0;
  transform-origin: bottom center;
  transition: all 0.7s ease-in-out;
}

.char-purple {
  left: 60px;
  z-index: 1;
  width: 170px;
  height: 370px;
  border-radius: 10px 10px 0 0;
  background: #6c3ff5;
}

.char-black {
  left: 220px;
  z-index: 2;
  width: 115px;
  height: 290px;
  border-radius: 8px 8px 0 0;
  background: #2d2d2d;
}

.char-orange {
  left: 0;
  z-index: 3;
  width: 230px;
  height: 190px;
  border-radius: 115px 115px 0 0;
  background: #ff9b6b;
}

.char-yellow {
  left: 290px;
  z-index: 4;
  width: 135px;
  height: 215px;
  border-radius: 68px 68px 0 0;
  background: #e8d754;
}

.eyes {
  position: absolute;
  display: flex;
  gap: 28px;
  transition: all 0.7s ease-in-out;
}

.eyes--bare {
  gap: 20px;
}

.eyeball {
  display: flex;
  width: 18px;
  height: 18px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 999px;
  background: #fff;
  transition: height 0.15s ease;
}

.eyeball--sm {
  width: 16px;
  height: 16px;
}

.pupil {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #2d2d2d;
  transition: transform 0.1s ease-out;
}

.pupil--sm {
  width: 6px;
  height: 6px;
}

.bare-pupil {
  width: 12px;
  height: 12px;
  border-radius: 999px;
  background: #2d2d2d;
  transition: transform 0.7s ease-in-out;
}

.yellow-mouth {
  position: absolute;
  width: 50px;
  height: 4px;
  border-radius: 2px;
  background: #2d2d2d;
  transition: all 0.7s ease-in-out;
}

.orange-mouth {
  position: absolute;
  width: 28px;
  height: 14px;
  opacity: 0;
  border: 3px solid #2d2d2d;
  border-top: none;
  border-radius: 0 0 14px 14px;
  transition: all 0.7s ease-in-out;
}

.orange-mouth.visible {
  opacity: 1;
}

@keyframes shakeHead {
  0%,
  100% {
    translate: 0 0;
  }

  10% {
    translate: -9px 0;
  }

  20% {
    translate: 7px 0;
  }

  30% {
    translate: -6px 0;
  }

  40% {
    translate: 5px 0;
  }

  50% {
    translate: -4px 0;
  }

  60% {
    translate: 3px 0;
  }

  70% {
    translate: -2px 0;
  }

  80% {
    translate: 1px 0;
  }

  90% {
    translate: -0.5px 0;
  }
}

.shake-head {
  animation: shakeHead 0.8s cubic-bezier(0.36, 0.07, 0.19, 0.97) both;
}

.panel-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.panel-footer span {
  display: inline-flex;
  align-items: center;
  border: 1px solid rgb(82 72 99 / 0.14);
  border-radius: 999px;
  padding: 7px 12px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: rgb(83 72 100 / 0.74);
  background: rgb(255 255 255 / 0.22);
  backdrop-filter: blur(10px);
}

@media (max-width: 960px) {
  .mascot-panel {
    min-height: auto;
  }

  .characters-wrapper {
    min-height: 360px;
  }
}
</style>
