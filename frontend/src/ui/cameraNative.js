const elements = {
  totalDistance: null,
  address: null,
  gps: null,
  time: null,
  gpsLevel: null,
  netLevel: null,
  lastPhoto: null,
  iriCanvas: null,
};

export const states = {
  timeOffset: 0,
};

const IRI_CONFIG = {
  maxDistance: 500, // 显示最近 500米
  dataQueue: [], // { dist: 累计距离, val: IRI值 }
  currentTotalDist: 0,
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
    elements.iriCanvas = document.getElementById("iri-canvas");
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

  updateGpsLevel: (gpsLevel) => {
    const text = ["无", "弱", "较弱", "较强", "强"][gpsLevel] || "无";
    elements.gpsLevel.innerText = text;
  },

  updateNetLevel: (netLevel) => {
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

  updateIriData: (iriValue, segmentLength) => {
    if (!elements.iriCanvas) return;

    // 1. 更新数据队列
    IRI_CONFIG.currentTotalDist += segmentLength;
    IRI_CONFIG.dataQueue.push({
      dist: IRI_CONFIG.currentTotalDist,
      val: iriValue,
    });

    // 2. 移除超出范围的老数据 (滑动窗口)
    const threshold = IRI_CONFIG.currentTotalDist - IRI_CONFIG.maxDistance;
    while (
      IRI_CONFIG.dataQueue.length > 0 &&
      IRI_CONFIG.dataQueue[0].dist < threshold
    ) {
      IRI_CONFIG.dataQueue.shift();
    }

    // 3. 绘制
    drawIriChart();
  },

  // 暴露给外部用于清空图表（开始新巡检时）
  resetIriChart: () => {
    IRI_CONFIG.dataQueue = [];
    IRI_CONFIG.currentTotalDist = 0;
    if (elements.iriCanvas) {
      drawIriChart();
    }
  },
};

function drawIriChart() {
  const canvas = elements.iriCanvas;
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  const queue = IRI_CONFIG.dataQueue;

  // 1. 清空画布
  ctx.clearRect(0, 0, canvas.width, canvas.height);

  // 2. 定义布局参数 (单位: px, 对应 canvas width=480, height=240)
  const margin = { top: 20, right: 20, bottom: 30, left: 40 };
  const graphWidth = canvas.width - margin.left - margin.right;
  const graphHeight = canvas.height - margin.top - margin.bottom;

  // Y轴量程 (0 - 9)
  const maxVal = 9;

  // --- 坐标映射辅助函数 ---

  // X轴: 距离 -> 像素坐标
  const getX = (d) => {
    // 窗口起始距离 = 当前总里程 - 500m
    const windowStart = Math.max(
      0,
      IRI_CONFIG.currentTotalDist - IRI_CONFIG.maxDistance
    );
    const windowEnd = Math.max(
      IRI_CONFIG.maxDistance,
      IRI_CONFIG.currentTotalDist
    );

    // 归一化 (0~1)
    const ratio = (d - windowStart) / (windowEnd - windowStart);
    // 映射到绘图区
    return margin.left + ratio * graphWidth;
  };

  // Y轴: IRI值 -> 像素坐标 (注意 Canvas Y轴向下)
  const getY = (v) => {
    const clamped = Math.min(Math.max(v, 0), maxVal);
    const ratio = clamped / maxVal;
    // 0 在 graphHeight (底部), maxVal 在 0 (顶部)
    return margin.top + graphHeight - ratio * graphHeight;
  };

  // --- 3. 绘制坐标轴与网格 ---

  ctx.save();
  // 设置文字样式
  ctx.fillStyle = "white";
  ctx.font = "bold 14px sans-serif";
  ctx.textAlign = "right";
  ctx.textBaseline = "middle";
  // 文字阴影 (防止背景太亮看不清)
  ctx.shadowColor = "rgba(0, 0, 0, 0.8)";
  ctx.shadowBlur = 4;
  ctx.shadowOffsetX = 1;
  ctx.shadowOffsetY = 1;

  // 3.1 绘制 Y 轴参考线和标签
  const ySteps = [0, 2, 4, 6, 8]; // 刻度

  ySteps.forEach((step) => {
    const y = getY(step);

    // A. 画虚线 (背景网格)
    ctx.beginPath();
    // 增强可视性：白色，0.5透明度
    ctx.strokeStyle = "rgba(255, 255, 255, 0.5)";
    ctx.lineWidth = 1; // 细线
    ctx.setLineDash([6, 4]); // 虚线模式: 6px实, 4px空
    ctx.moveTo(margin.left, y);
    ctx.lineTo(margin.left + graphWidth, y);
    ctx.stroke();

    // B. 画左侧文字 (0, 2, 4...)
    // 移除虚线模式画文字
    ctx.setLineDash([]);
    ctx.fillText(step.toString(), margin.left - 8, y);
  });

  // 3.2 绘制 X 轴标签 (里程)
  ctx.textAlign = "center";
  ctx.textBaseline = "top"; // 文字在轴下方

  // 计算当前窗口的里程范围
  const startDist = Math.max(
    0,
    IRI_CONFIG.currentTotalDist - IRI_CONFIG.maxDistance
  );
  const endDist = Math.max(IRI_CONFIG.maxDistance, IRI_CONFIG.currentTotalDist);

  // 左侧标签 (起点)
  const startLabel = (startDist / 1000).toFixed(1) + "km";
  ctx.fillText(startLabel, margin.left, margin.top + graphHeight + 8);

  // 右侧标签 (终点/当前)
  const endLabel = (endDist / 1000).toFixed(1) + "km";
  ctx.fillText(
    endLabel,
    margin.left + graphWidth,
    margin.top + graphHeight + 8
  );

  // 3.3 绘制坐标轴实线 (左轴和底轴)
  ctx.strokeStyle = "white";
  ctx.lineWidth = 2;
  ctx.setLineDash([]); // 恢复实线
  ctx.beginPath();
  // Y轴
  ctx.moveTo(margin.left, margin.top);
  ctx.lineTo(margin.left, margin.top + graphHeight);
  // X轴
  ctx.lineTo(margin.left + graphWidth, margin.top + graphHeight);
  ctx.stroke();

  ctx.restore();

  // --- 4. 绘制数据曲线 ---

  if (queue.length < 2) return;

  ctx.lineWidth = 3;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";

  // 必须重置虚线，防止数据线变虚
  ctx.setLineDash([]);

  for (let i = 0; i < queue.length - 1; i++) {
    const p1 = queue[i];
    const p2 = queue[i + 1];

    // 如果点在视图外，稍微优化一下可以不画，但 Canvas 自身剪裁效率很高，直接画也没事

    ctx.beginPath();
    ctx.moveTo(getX(p1.dist), getY(p1.val));
    ctx.lineTo(getX(p2.dist), getY(p2.val));

    // 颜色判定
    const val = (p1.val + p2.val) / 2;
    ctx.strokeStyle = getIriColor(val);
    ctx.stroke();
  }
}

function getIriColor(val) {
  if (val <= 2.0) return "#00E676"; // 绿
  if (val <= 4.0) return "#FFFF00"; // 黄
  if (val <= 6.0) return "#FF9100"; // 橙
  if (val <= 8.0) return "#FF1744"; // 红
  return "#D500F9"; // 紫
}

export default UI;
