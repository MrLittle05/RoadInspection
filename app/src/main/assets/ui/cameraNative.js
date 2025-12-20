const elements = {
  totalDistance: null,
  address: null,
  gps: null,
  time: null,
  gpsLevel: null,
  netLevel: null,
  lastPhoto: null,
};

export const states = {
  timeOffset: 0,
};

const UI = {
  initElements: () => {
    elements.totalDistance = document.getElementById("total-dist");
    elements.address = document.getElementById("address");
    elements.gps = document.getElementById("gps-raw");
    elements.time = document.getElementById("time");
    elements.gpsLevel = document.getElementById("gps-accuracy");
    elements.netLevel = document.getElementById("net-strength");
    elements.lastPhoto = document.getElementById("last-photo");
  },

  updateTotalDistance: (newTotalDistance) => {
    if (newTotalDistance !== undefined && newTotalDistance !== null) {
      elements.totalDistance.innerText = (newTotalDistance / 1000).toFixed(3);
    }
  },

  updatePosition: (newLat, newLng) => {
    // 保留 6 位小数 (精度~1米以内)，不够补0
    const latDir = newLat ? (newLat > 0 ? "N" : "S") : "";
    const latStr = (Math.abs(newLat) || 0).toFixed(6);
    const lonDir = newLng ? (newLng > 0 ? "E" : "W") : "";
    const lonStr = (Math.abs(newLng) || 0).toFixed(6);

    // 显示: N:31.230416 E:121.473701
    elements.gps.innerText = `${latDir}:${latStr}  ${lonDir}:${lonStr}`;
  },

  updateTimeOffset: (timeOffset) => {
    if (timeOffset !== undefined || timeOffset !== null)
      states.timeOffset = timeOffset;
  },

  updateTime: (timeOffset) => {
    // 核心逻辑：当前系统时间 + 偏差值 = 真实的 GPS 时间
    // 如果没有 GPS 信号，timeOffset 为 0，则显示系统时间
    const currentTimestamp = Date.now() + timeOffset;

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

    elements.time.innerText = `${dateStr}  ${timeStr}`;
  },

  updateAddress: (addr) => {
    elements.address.innerText = addr;
  },

  updateGpsSignal: (gpsLevel) => {
    const text = ["无", "弱", "较弱", "较强", "强"][gpsLevel] || "无";
    elements.gpsLevel.innerText = text;
  },

  updateNetSignal: (netLevel) => {
    const text = ["无", "弱", "较弱", "较强", "强"][netLevel] || "无";
    elements.netLevel.innerText = text;
  },

  updateLatestPhoto: (uri) => {
    const img = elements.lastPhoto;
    if (img) {
      img.src = uri;
      img.style.display = "block";
    }
  },
};

// 将模块内的函数暴露到全局 window 对象上，以便原生代码可以调用
window.updateLatestPhoto = UI.updateLatestPhoto;

export default UI;
