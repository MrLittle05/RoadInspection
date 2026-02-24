import nativeUI from "./cameraNative.js";

const elements = {
  infoBtn: null,
  zoom1Btn: null,
  zoom05Btn: null,
  modePhotoBtn: null,
  modeVideoBtn: null,
  galleryBtn: null,
  shutterBtn: null,
  aiBtn: null,
  timer: null,
  distValRow: null,
  galleryWrapper: null,
  pauseWrapper: null,
  iconPause: null,
  iconResume: null,
  exitModal: null,
  btnModalResume: null,
  btnModalSaveExit: null,
};

const states = {
  currentMode: "video",
  isRecording: false,
  isPaused: false,
  recordSeconds: 0,
  currentRotationState: 0,
  timerInterval: null,
};

const UI = {
  initElements: () => {
    elements.infoBtn = document.getElementById("btn-info");
    elements.zoom1Btn = document.getElementById("btn-zoom-1");
    elements.zoom05Btn = document.getElementById("btn-zoom-05");
    elements.modePhotoBtn = document.getElementById("btn-mode-photo");
    elements.modeVideoBtn = document.getElementById("btn-mode-video");
    elements.galleryBtn = document.getElementById("btn-gallery");
    elements.shutterBtn = document.getElementById("btn-shutter");
    elements.aiBtn = document.getElementById("btn-ai");
    elements.timer = document.getElementById("timer");
    elements.distValRow = document.querySelector(".dist-val-row");
    elements.galleryWrapper = document.getElementById("gallery-wrapper");
    elements.pauseWrapper = document.getElementById("pause-wrapper");
    elements.iconPause = document.getElementById("icon-pause");
    elements.iconResume = document.getElementById("icon-resume");
    elements.exitModal = document.getElementById("exit-modal");
    elements.btnModalResume = document.getElementById("btn-modal-resume");
    elements.btnModalSaveExit = document.getElementById("btn-modal-save-exit");
  },

  initJSBridgeCallback: () => {
    if (window.JSBridge) {
      window.JSBridge.registerBackPressHandler(UI.onBackProceed);
    }
  },

  bind: (eventName, callback) => {
    switch (eventName) {
      case "info":
        elements.infoBtn.addEventListener("click", callback);
        break;
      case "zoom1":
        elements.zoom1Btn.addEventListener("click", callback);
        break;
      case "zoom05":
        elements.zoom05Btn.addEventListener("click", callback);
        break;
      case "modePhoto":
        elements.modePhotoBtn.addEventListener("click", callback);
        break;
      case "modeVideo":
        elements.modeVideoBtn.addEventListener("click", callback);
        break;
      case "gallery":
        elements.galleryBtn.addEventListener("click", callback);
        break;
      case "shutter":
        elements.shutterBtn.addEventListener("click", callback);
        break;
      case "ai":
        elements.aiBtn.addEventListener("click", callback);
        break;
      case "rotation":
        window.addEventListener("deviceorientation", callback);
        break;
      case "resume":
        elements.btnModalResume.addEventListener("click", callback);
        break;
      case "saveExit":
        elements.btnModalSaveExit.addEventListener("click", callback);
        break;
    }
  },

  handleGalleryClick: () => {
    if (states.isRecording) {
      togglePause();
    } else {
      openGallery();
    }
  },

  toggleDisplayInfo: () => {
    const textInfoDiv = document.querySelector(".text-info");
    textInfoDiv.classList.contains("info-hidden")
      ? textInfoDiv.classList.remove("info-hidden")
      : textInfoDiv.classList.add("info-hidden");
  },

  setZoom: (val) => {
    document
      .querySelectorAll(".zoom-btn")
      .forEach((b) => b.classList.remove("active"));
    const targetId = val === 1 ? "btn-zoom-1" : "btn-zoom-05";
    document.getElementById(targetId).classList.add("active");
    window.AndroidNative.setZoom(val);
  },

  switchMode: (mode) => {
    if (states.isRecording) return;
    states.currentMode = mode;

    // 更新文字样式
    document
      .querySelectorAll(".mode-item")
      .forEach((el) => el.classList.remove("active"));
    document.getElementById("btn-mode-" + mode).classList.add("active");

    // 更新快门样式; 切换是否显示计时器
    const inner = document.getElementById("shutter-btn");
    if (mode === "photo") {
      elements.timer.classList.add("hidden");
      inner.classList.add("photo-mode");
    } else {
      elements.timer.classList.remove("hidden");
      inner.classList.remove("photo-mode");
    }
  },

  handleShutter: () => {
    if (states.currentMode === "photo") {
      window.AndroidNative.manualCapture();
      animateShutter();
    } else {
      if (!states.isRecording) {
        const params = Object.fromEntries(
          new URLSearchParams(window.location.search),
        );
        startRecording(params.taskName, params.userId);
      } else {
        stopRecording();
        setTimeout(() => {
          window.AndroidNative.stopInspectionActivity();
        }, 1000);
      }
    }
  },

  toggleAI: () => {
    window.AndroidNative.showToast("AI 算法配置中");
  },

  handleOrientation: (event) => {
    let { beta, gamma } = event;

    const nextRotation = calculateNextRotation(
      beta,
      gamma,
      states.currentRotationState,
    );

    if (nextRotation !== states.currentRotationState) {
      states.currentRotationState = nextRotation;

      const layoutRoot = document.querySelector(".layout-root");
      layoutRoot.classList.remove(
        "mode-portrait",
        "mode-tilt-left",
        "mode-tilt-right",
      );

      if (states.currentRotationState === 0) {
        layoutRoot.classList.add("mode-portrait");
        elements.distValRow.style.flexDirection = "row";
      } else if (states.currentRotationState === 90) {
        layoutRoot.classList.add("mode-tilt-left");
        elements.distValRow.style.flexDirection = "row-reverse";
      } else if (states.currentRotationState === -90) {
        layoutRoot.classList.add("mode-tilt-right");
        elements.distValRow.style.flexDirection = "row";
      }

      document.querySelectorAll(".rotatable").forEach((el) => {
        el.style.transform = `rotate(${states.currentRotationState}deg)`;
      });
    }
  },

  onBackProceed: () => {
    if (states.isRecording) {
      UI.showExitModal();
    } else {
      window.AndroidNative.stopInspectionActivity();
    }
  },

  showExitModal: () => {
    elements.exitModal.classList.remove("hidden");
  },

  dismissExitModal: () => {
    elements.exitModal.classList.add("hidden");
  },

  handleSaveAndExit: () => {
    UI.dismissExitModal();
    window.AndroidNative.saveInspectionState();
    setTimeout(() => {
      window.AndroidNative.stopInspectionActivity();
    }, 800);
  },

  restoreState: (data) => {
    // 1. 恢复状态变量
    states.isRecording = true;
    states.isPaused = true;
    states.recordSeconds = data.seconds || 0;

    // 2. 更新计时器显示
    updateTimer();

    // 3. 恢复计时器循环
    startTimerLoop();

    // 3. 更新按钮显示 (显示“恢复”按钮，隐藏“暂停”按钮)
    elements.galleryWrapper.classList.add("hidden");
    elements.pauseWrapper.classList.remove("hidden");
    elements.iconPause.classList.add("hidden");
    elements.iconResume.classList.remove("hidden");

    // 4. 给快门按钮添加录制样式 (红点)
    document.getElementById("shutter-btn").classList.add("recording");
    if (elements.timer) elements.timer.classList.add("active");
  },
};

function togglePause() {
  states.isPaused = !states.isPaused;

  if (states.isPaused) {
    elements.iconPause.classList.add("hidden");
    elements.iconResume.classList.remove("hidden");
    window.AndroidNative.pauseInspection();
    console.log("Notify Android to PAUSE inspection");
  } else {
    elements.iconPause.classList.remove("hidden");
    elements.iconResume.classList.add("hidden");
    window.AndroidNative.resumeInspection();
    console.log("Notify Android to RESUME inspection");
  }
}

function startTimerLoop() {
  // 防止重复启动，先清除旧的
  if (states.timerInterval) clearInterval(states.timerInterval);

  states.timerInterval = setInterval(() => {
    // 只有在“录制中”且“非暂停”状态下才累加时间
    if (states.isRecording && !states.isPaused) {
      states.recordSeconds++;
      updateTimer();
    }
  }, 1000);
}

function openGallery() {
  window.AndroidNative.openGallery("all");
}

function animateShutter() {
  const btn = document.querySelector(".shutter-inner");
  btn.style.transform = "scale(0.5)";
  setTimeout(() => (btn.style.transform = "scale(1)"), 100);

  // 闪屏效果
  const flash = document.createElement("div");
  flash.style.cssText =
    "position:fixed;top:0;left:0;width:100%;height:100%;background:black;opacity:0.6;z-index:999;transition:opacity 0.2s;";
  document.body.appendChild(flash);
  setTimeout(() => (flash.style.opacity = 0), 50);
  setTimeout(() => flash.remove(), 250);
}

function startRecording(taskName, userId) {
  if (!userId) {
    window.AndroidNative.showToast("请先登录，再开始巡检！");
    return;
  }
  states.isRecording = true;
  states.isPaused = false;

  document.getElementById("shutter-btn").classList.add("recording");
  if (elements.timer) {
    elements.timer.classList.add("active");
  }

  // Toggle Gallery Button to Pause Button
  elements.galleryWrapper.classList.add("hidden");
  elements.pauseWrapper.classList.remove("hidden");
  elements.iconPause.classList.remove("hidden");
  elements.iconResume.classList.add("hidden");
  window.AndroidNative.startInspection(taskName, userId);

  // 启动计时器
  states.recordSeconds = 0;
  updateTimer();

  startTimerLoop();

  // 重置图表数据
  nativeUI.resetIriChart();

  // 显示图表
  const canvas = document.getElementById("iri-canvas");
  if (canvas) canvas.classList.remove("hidden");
}

function stopRecording() {
  states.isRecording = false;
  document.getElementById("shutter-btn").classList.remove("recording");
  if (elements.timer) {
    elements.timer.classList.remove("active");
  }

  elements.pauseWrapper.classList.add("hidden");
  elements.galleryWrapper.classList.remove("hidden");
  window.AndroidNative.stopInspection();
  states.recordSeconds = 0;
  updateTimer();
  clearInterval(states.timerInterval);

  // 隐藏图表
  const canvas = document.getElementById("iri-canvas");
  if (canvas) canvas.classList.add("hidden");
}

function updateTimer() {
  const h = Math.floor(states.recordSeconds / 3600)
    .toString()
    .padStart(2, "0");
  const m = Math.floor((states.recordSeconds % 3600) / 60)
    .toString()
    .padStart(2, "0");
  const s = (states.recordSeconds % 60).toString().padStart(2, "0");
  elements.timer.innerText = `${h}:${m}:${s}`;
}

function calculateNextRotation(beta, gamma, currentRotationState) {
  // --- 1. 数据标准化 ---
  // 修正倒拿手机的情况
  if (beta > 90) beta = 180 - beta;
  if (beta < -90) beta = -180 - beta;

  const absGamma = Math.abs(gamma);
  const absBeta = Math.abs(beta);

  // --- 2. 全局死区 ---
  // 极其平躺时直接忽略
  if (absBeta < 10 && absGamma < 10) return currentRotationState;

  // --- 3. 核心逻辑 ---
  let nextRotation = currentRotationState;
  const isLandscape =
    currentRotationState === 90 || currentRotationState === -90;

  // 【关键修复点】：定义“足够直立”
  // 只有当手机支棱起来（大于30度）时，才允许改变横竖状态，或者左右互换
  // 如果你在仰拍 (absBeta < 30)，无论怎么晃，都锁死在当前状态
  const isUprightEnough = absBeta > 30;

  if (!isLandscape) {
    // === 当前是竖屏 ===
    // 竖屏切横屏，通常不需要太严格的 Beta 限制，因为用户可能就是想平着拿看视频
    // 但为了防抖，维持 Gamma 必须显著大于 Beta
    if (absGamma > absBeta + 15) {
      nextRotation = gamma > 0 ? -90 : 90;
    }
  } else {
    // === 当前是横屏 ===

    // 如果你在仰拍 (不够直立)，直接跳过所有判断，保持原样！
    // 这样既不会切回竖屏，也不会左右乱跳
    if (isUprightEnough) {
      // 1. 检查是否切回竖屏
      // Beta 必须显著大于 Gamma
      if (absBeta > absGamma + 15) {
        nextRotation = 0;
      }
      // 2. 检查是否左右互换 (180度翻转)
      // 只有在直立状态下，才允许根据 Gamma 正负切换左右
      else {
        if (gamma > 20) nextRotation = -90; // 右倒
        if (gamma < -20) nextRotation = 90; // 左倒
      }
    }
  }
  return nextRotation;
}

export default UI;
