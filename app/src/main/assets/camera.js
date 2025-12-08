// === 状态管理 ===
let currentMode = 'video'; // 'video' 或 'photo'
let isRecording = false;
let timerInterval = null;
let recordSeconds = 0;

// Mock 接口 (用于浏览器调试)
if (!window.AndroidNative) {
window.AndroidNative = {
    startInspection: () => console.log("Mock: Start Inspection"),
    stopInspection: () => console.log("Mock: Stop Inspection"),
    manualCapture: () => console.log("Mock: Take Photo"),
    showToast: (msg) => console.log("Toast: " + msg),
    openGallery: (type) => console.log("Open Gallery: " + type)
};
}

// === 交互逻辑 ===

// 切换模式
function switchMode(mode) {
    if (isRecording) return; // 录制中禁止切换
    currentMode = mode;

    // 更新文字样式
    document.querySelectorAll('.mode-item').forEach(el => el.classList.remove('active'));
    document.getElementById('mode-' + mode).classList.add('active');

    // 更新快门样式
    const inner = document.getElementById('shutter-btn');
    if (mode === 'photo') {
        inner.classList.add('photo-mode');
    } else {
        inner.classList.remove('photo-mode');
    }
}

// 点击快门
function handleShutter() {
    if (currentMode === 'photo') {
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
    const btn = document.querySelector('.shutter-outer');
    btn.style.transform = "scale(0.9)";
    setTimeout(() => btn.style.transform = "scale(1)", 100);
}

function startRecording() {
    isRecording = true;
    document.getElementById('shutter-btn').classList.add('recording');
    window.AndroidNative.startInspection(); // 调用 Native 开启轨迹记录

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
    document.getElementById('shutter-btn').classList.remove('recording');
    window.AndroidNative.stopInspection(); // 调用 Native 停止

    clearInterval(timerInterval);
}

function updateTimerUI() {
    const h = Math.floor(recordSeconds / 3600).toString().padStart(2, '0');
    const m = Math.floor((recordSeconds % 3600) / 60).toString().padStart(2, '0');
    const s = (recordSeconds % 60).toString().padStart(2, '0');
    document.getElementById('timer').innerText = `${h}:${m}:${s}`;
}

function toggleAI() {
    window.AndroidNative.showToast("AI 算法配置中");
}

function openGallery() {
    // 根据文档，查看全部相册
    window.AndroidNative.openGallery("all");
}

function setZoom(val) {
     document.querySelectorAll('.zoom-btn').forEach(b => b.classList.remove('active'));
     event.target.classList.add('active');
     window.AndroidNative.showToast("缩放: " + val + "x");
     // 实际项目中这里应调用 Native 接口设置相机变焦
}

function toggleDisplayInfo() {
    const textInfoDiv = document.querySelector(".text-info");
    textInfoDiv.classList.contains("info-hidden") ? textInfoDiv.classList.remove("info-hidden") : textInfoDiv.classList.add("info-hidden");
}

// === Native 回调接口 (window.JSBridge) ===

window.JSBridge = {
    // 1. 更新仪表盘 (位置/速度/距离)
    updateDashboard: function(jsonStr) {
        try {
            const data = JSON.parse(jsonStr);
            // 距离
            if (data.totalDistance !== undefined) {
                document.getElementById('total-dist').innerText = (data.totalDistance / 1000).toFixed(3);
            }
            // 顶部信息: 速度 + 时间
            const now = new Date(data.timestamp || Date.now());
            const timeStr = now.toTimeString().split(' ')[0]; // HH:MM:SS
            document.getElementById('gps-raw').innerText =
                `S:${(data.speed||0).toFixed(1)} N:${(data.lat||0).toFixed(4)} ${timeStr}`;

            // 信号强度演示
            const acc = data.gpsAccuracy || 999;
            document.getElementById('gps-accuracy').innerText = acc < 10 ? "强" : (acc < 30 ? "中" : "弱");

        } catch(e) { console.error("Data parse error", e); }
    },

    // 2. 更新地址文字
    updateAddress: function(addr) {
        document.getElementById('address').innerText = addr;
    },

    // 3. 拍照成功回调
    onPhotoTaken: function(jsonStr) {
        try {
            const data = JSON.parse(jsonStr);
            const img = document.getElementById('last-photo');
            // 注意：前端加载本地文件通常需要 file:// 协议
            img.src = "file://" + data.filePath;
            img.style.display = "block";

            // 闪屏效果
            const flash = document.createElement('div');
            flash.style.cssText = "position:fixed;top:0;left:0;width:100%;height:100%;background:white;opacity:0.6;z-index:999;transition:opacity 0.2s;";
            document.body.appendChild(flash);
            setTimeout(() => flash.style.opacity = 0, 50);
            setTimeout(() => flash.remove(), 250);
        } catch(e) { console.error(e); }
    }
}