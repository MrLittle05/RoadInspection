import nativeUI from "../ui/cameraNative.js";

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

  onPhotoTaken: function (jsonStr) {
    try {
      const data = JSON.parse(jsonStr);
      nativeUI.onPhotoTaken(data);
    } catch (e) {
      console.error(e);
    }
  },
};
