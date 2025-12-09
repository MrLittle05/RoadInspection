// === 状态管理 ===
let currentMode = "video"; // 'video' 或 'photo'
let isRecording = false;
let timerInterval = null;
let recordSeconds = 0;

const dataUI = {
  timer: document.getElementById("timer"),
  totalDistance: document.getElementById("total-dist"),
  gpsAndTime: document.getElementById("gps-raw"),
  gpsLevel: document.getElementById("gps-accuracy"),
  netLevel: document.getElementById("net-strength"),
};

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

  // 更新快门样式
  const inner = document.getElementById("shutter-btn");
  if (mode === "photo") {
    inner.classList.add("photo-mode");
  } else {
    inner.classList.remove("photo-mode");
  }
}

// 点击快门
function handleShutter() {
  if (currentMode === "photo") {
    // 拍照模式：直接触发一次抓拍
    window.AndroidNative.manualCapture();
    animateShutter();
  } else {
    // 视频模式：开始/停止 巡检
    if (!isRecording) {
      startRecording();
    } else {
      stopRecording();
    }
  }
}

function animateShutter() {
  const btn = document.querySelector(".shutter-outer");
  btn.style.transform = "scale(0.9)";
  setTimeout(() => (btn.style.transform = "scale(1)"), 100);
}

function startRecording() {
  isRecording = true;
  document.getElementById("shutter-btn").classList.add("recording");
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
  window.AndroidNative.stopInspection();

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

// === Native 回调接口 (window.JSBridge) ===

window.JSBridge = {
  updateDashboard: function (jsonStr) {
    try {
      const data = JSON.parse(jsonStr);
      // 总距离
      if (data.totalDistance !== undefined) {
        dataUI.totalDistance.innerText = (data.totalDistance / 1000).toFixed(3);
      }

      // 顶部信息: 纬度 + 纬度 + 时间  (YY/MM/DD HH:MM:SS)
      // 传入 { lat: 31.230416, lng: 121.473701, timestamp: 1715000000000 }

      // 时间 (优先用 GPS 时间)
      const dateObj = new Date(
        data.timestamp && data.timestamp > 0 ? data.timestamp : Date.now()
      );

      const dateStr =
        dateObj.getFullYear() +
        "/" +
        String(dateObj.getMonth() + 1).padStart(2, "0") +
        "/" +
        String(dateObj.getDate()).padStart(2, "0");

      const timeStr =
        String(dateObj.getHours()).padStart(2, "0") +
        ":" +
        String(dateObj.getMinutes()).padStart(2, "0") +
        ":" +
        String(dateObj.getSeconds()).padStart(2, "0");

      // 经纬度
      // 建议保留 6 位小数 (精度~1米以内)，不够补0
      const latStr = (data.lat || 0).toFixed(6);
      const lonStr = (data.lon || data.lng || 0).toFixed(6); // 兼容 lon 或 lng 字段名

      // 显示: N:31.230416 E:121.473701 2025/05/06 12:12:12
      dataUI.gpsAndTime.innerText = `N:${latStr}  E:${lonStr}  ${dateStr} ${timeStr}`;

      // GPS信号强度
      const gpsLevel = data.gpsLevel || 999;
      dataUI.gpsLevel.innerText =
        gpsLevel === 4
          ? "强"
          : gpsLevel === 3
          ? "较强"
          : gpsLevel === 2
          ? "较弱"
          : gpsLevel === 1
          ? "弱"
          : "无";

      // 网络信号强度
      const netLevel = data.netLevel || 999;
      dataUI.netLevel.innerText =
        netLevel === 4
          ? "强"
          : netLevel === 3
          ? "较强"
          : netLevel === 2
          ? "较弱"
          : netLevel === 1
          ? "弱"
          : "无";
    } catch (e) {
      console.error("Data parse error", e);
    }
  },

  // 2. 更新地址文字
  updateAddress: function (addr) {
    document.getElementById("address").innerText = addr;
  },

  // 3. 拍照成功回调
  onPhotoTaken: function (jsonStr) {
    try {
      const data = JSON.parse(jsonStr);
      const img = document.getElementById("last-photo");
      // 注意：前端加载本地文件通常需要 file:// 协议
      img.src = "file://" + data.filePath;
      img.style.display = "block";

      // 闪屏效果
      const flash = document.createElement("div");
      flash.style.cssText =
        "position:fixed;top:0;left:0;width:100%;height:100%;background:white;opacity:0.6;z-index:999;transition:opacity 0.2s;";
      document.body.appendChild(flash);
      setTimeout(() => (flash.style.opacity = 0), 50);
      setTimeout(() => flash.remove(), 250);
    } catch (e) {
      console.error(e);
    }
  },
};
