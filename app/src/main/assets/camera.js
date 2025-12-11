// === 状态管理 ===
let currentMode = "video"; // 'video' 或 'photo'
let isRecording = false;
let timerInterval = null;
let recordSeconds = 0;

const dataUI = {
  timer: null,
  totalDistance: null,
  gps: null,
  time: null,
  gpsLevel: null,
  netLevel: null,
  lastPhoto: null,
  timeOffset: 0,
};

document.addEventListener("DOMContentLoaded", () => {
  (dataUI.timer = document.getElementById("timer")),
    (dataUI.totalDistance = document.getElementById("total-dist")),
    (dataUI.gps = document.getElementById("gps-raw")),
    (dataUI.time = document.getElementById("time")),
    (dataUI.gpsLevel = document.getElementById("gps-accuracy")),
    (dataUI.netLevel = document.getElementById("net-strength")),
    (dataUI.lastPhoto = document.getElementById("last-photo"));

  // 初始化并在每秒更新时间
  // initState();

  setInterval(() => {
    updateTime();
  }, 1000);
});

// Mock 接口 (用于浏览器调试)
if (!window.AndroidNative) {
  window.AndroidNative = {
    startInspection: () => console.log("Mock: Start Inspection"),
    stopInspection: () => console.log("Mock: Stop Inspection"),
    manualCapture: () => console.log("Mock: Take Photo"),
    showToast: (msg) => console.log("Toast: " + msg),
    openGallery: (type) => console.log("Open Gallery: " + type),
  };
}

// === 交互逻辑 ===

// 切换模式
function switchMode(mode) {
  if (isRecording) return; // 录制中禁止切换
  currentMode = mode;

  // 更新文字样式
  document
    .querySelectorAll(".mode-item")
    .forEach((el) => el.classList.remove("active"));
  document.getElementById("mode-" + mode).classList.add("active");

  // 更新快门样式; 切换是否显示计时器
  const inner = document.getElementById("shutter-btn");
  if (mode === "photo") {
    dataUI.timer.classList.add("hidden");
    inner.classList.add("photo-mode");
  } else {
    dataUI.timer.classList.remove("hidden");
    inner.classList.remove("photo-mode");
  }
}

// 点击快门
function handleShutter() {
  if (currentMode === "photo") {
    window.AndroidNative.manualCapture();
    animateShutter();
  } else {
    if (!isRecording) {
      startRecording();
    } else {
      stopRecording();
    }
  }
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

function startRecording() {
  isRecording = true;
  document.getElementById("shutter-btn").classList.add("recording");
  if (dataUI.timer) {
    dataUI.timer.classList.add("active");
  }
  window.AndroidNative.startInspection();

  // 启动计时器
  recordSeconds = 0;
  updateTimerUI();
  timerInterval = setInterval(() => {
    recordSeconds++;
    updateTimerUI();
  }, 1000);
}

function stopRecording() {
  isRecording = false;
  document.getElementById("shutter-btn").classList.remove("recording");
  if (dataUI.timer) {
    dataUI.timer.classList.remove("active");
  }
  window.AndroidNative.stopInspection();
  recordSeconds = 0;
  updateTimerUI();
  clearInterval(timerInterval);
}

function updateTimerUI() {
  const h = Math.floor(recordSeconds / 3600)
    .toString()
    .padStart(2, "0");
  const m = Math.floor((recordSeconds % 3600) / 60)
    .toString()
    .padStart(2, "0");
  const s = (recordSeconds % 60).toString().padStart(2, "0");
  dataUI.timer.innerText = `${h}:${m}:${s}`;
}

function toggleAI() {
  window.AndroidNative.showToast("AI 算法配置中");
}

function openGallery() {
  window.AndroidNative.openGallery("all");
}

function setZoom(val) {
  document
    .querySelectorAll(".zoom-btn")
    .forEach((b) => b.classList.remove("active"));
  event.target.classList.add("active");
  window.AndroidNative.showToast("缩放: " + val + "x");
  // 实际项目中这里应调用 Native 接口设置相机变焦
}

function toggleDisplayInfo() {
  const textInfoDiv = document.querySelector(".text-info");
  textInfoDiv.classList.contains("info-hidden")
    ? textInfoDiv.classList.remove("info-hidden")
    : textInfoDiv.classList.add("info-hidden");
}

function updateTime() {
  // 核心逻辑：当前系统时间 + 偏差值 = 真实的 GPS 时间
  // 如果没有 GPS 信号，timeOffset 为 0，则显示系统时间
  const currentTimestamp = Date.now() + dataUI.timeOffset;

  const curTime = new Date(currentTimestamp);

  const dateStr =
    curTime.getFullYear() +
    "/" +
    String(curTime.getMonth() + 1).padStart(2, "0") +
    "/" +
    String(curTime.getDate()).padStart(2, "0");

  const timeStr =
    String(curTime.getHours()).padStart(2, "0") +
    ":" +
    String(curTime.getMinutes()).padStart(2, "0") +
    ":" +
    String(curTime.getSeconds()).padStart(2, "0");

  dataUI.time.innerText = `${dateStr}  ${timeStr}`;
}

// === Native 回调接口 (window.JSBridge) ===

window.JSBridge = {
  // 1. 高频数据：距离、位置、时间差
  updateDashboard: function (jsonStr) {
    try {
      const data = JSON.parse(jsonStr);
      // 总距离
      if (data.totalDistance !== undefined) {
        dataUI.totalDistance.innerText = (data.totalDistance / 1000).toFixed(3);
      }

      // Native 端需计算：diff = location.getTime() - System.currentTimeMillis()
      // 如果 Native 传了 timeDiff，更新本地的偏差值
      // 让 setInterval 里的 updateTime() 自动读取最新的 timeOffset 进行渲染。
      // 这样既平滑，又准。
      if (data.timeDiff !== undefined && data.timeDiff !== null) {
        dataUI.timeOffset = data.timeDiff;
      }

      // 经纬度
      // 建议保留 6 位小数 (精度~1米以内)，不够补0
      const latDir = data?.lat ? (data.lat > 0 ? "N" : "S") : "";

      const latStr = (Math.abs(data.lat) || 0).toFixed(6);

      const lonDir = data?.lng ? (data.lng > 0 ? "E" : "W") : "";

      const lonStr = (Math.abs(data.lng) || 0).toFixed(6);

      // 显示: N:31.230416 E:121.473701
      dataUI.gps.innerText = `${latDir}:${latStr}  ${lonDir}:${lonStr}`;
    } catch (e) {
      console.error("Data parse error", e);
    }
  },

  updateGpsSignal: function (gpsLevel) {
    // level 建议直接传数字，不需要 JSON.parse，效率更高
    // 0=无, 1=弱, 2=较弱, 3=较强, 4=强
    const text = ["无", "弱", "较弱", "较强", "强"][gpsLevel] || "无";
    dataUI.gpsLevel.innerText = text;
  },

  updateNetSignal: function (netLevel) {
    // level: 0-4
    const text = ["无", "弱", "较弱", "较强", "强"][netLevel] || "无";
    dataUI.netLevel.innerText = text;
  },

  // 3. 更新地址文字 (低频，通常几秒一次或位置显著变化时)
  updateAddress: function (addr) {
    document.getElementById("address").innerText = addr;
  },

  // 4. 拍照成功回调
  onPhotoTaken: function (jsonStr) {
    try {
      const data = JSON.parse(jsonStr);
      const img = dataUI.lastPhoto;
      // 注意：前端加载本地文件通常需要 file:// 协议
      img.src = "file://" + data.filePath;
      img.style.display = "block";
    } catch (e) {
      console.error(e);
    }
  },
};
