import nativeUI from "../ui/cameraNative.js";
import userUI from "../ui/cameraUser.js";

let onBackPressCallback = null;

window.JSBridge = {
  // 1. 更新高频数据：距离、位置、时间差
  updateDashboard: function (jsonStr) {
    try {
      const data = JSON.parse(jsonStr);

      nativeUI.updateTotalDistance(data.totalDistance);

      nativeUI.updateTimeOffset(data.timeDiff);

      nativeUI.updatePosition(data.lat, data.lng);
    } catch (e) {
      console.error("Data parse error", e);
    }
  },

  updateGpsLevel: function (gpsLevel) {
    nativeUI.updateGpsLevel(gpsLevel);
  },

  updateNetLevel: function (netLevel) {
    nativeUI.updateNetLevel(netLevel);
  },

  updateAddress: function (addr) {
    nativeUI.updateAddress(addr);
  },

  updateLatestPhoto: function (uri) {
    nativeUI.updateLatestPhoto(uri);
  },

  updateIriData: function (iriValue, segmentLength) {
    nativeUI.updateIriData(iriValue, segmentLength);
  },

  registerBackPressHandler: function (callback) {
    onBackPressCallback = callback;
  },

  onNativeBackPressed: function () {
    if (onBackPressCallback) {
      // 如果注册了业务逻辑，交给业务层处理 (如：判断是否录制、是否显示弹窗)
      onBackPressCallback();
    } else {
      // 兜底：如果没有注册任何逻辑，直接让原生退出，防止死锁
      if (window.AndroidNative) {
        window.AndroidNative.stopInspectionActivity();
      }
    }
  },

  /**
   * 原生端调用：恢复巡检状态
   * @param data { distance, seconds }
   */
  onRestoreState: function (data) {
    nativeUI.restoreState(data);
    userUI.restoreState(data);
  },
};
