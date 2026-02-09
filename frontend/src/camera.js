import "./api/bridge.js";
import nativeUI, { states } from "./ui/cameraNative.js";
import userUI from "./ui/cameraUser.js";

document.addEventListener("DOMContentLoaded", init());

function init() {
  userUI.initElements();
  userUI.initJSBridgeCallback();
  nativeUI.initElements();

  initListeners();

  setInterval(() => {
    nativeUI.updateTime(states.timeOffset);
  }, 1000);
}

function initListeners() {
  userUI.bind("info", userUI.toggleDisplayInfo);
  userUI.bind("zoom1", () => userUI.setZoom(1));
  userUI.bind("zoom05", () => userUI.setZoom(0.5));
  userUI.bind("modePhoto", () => userUI.switchMode("photo"));
  userUI.bind("modeVideo", () => userUI.switchMode("video"));
  userUI.bind("gallery", userUI.handleGalleryClick);
  userUI.bind("shutter", userUI.handleShutter);
  userUI.bind("ai", userUI.toggleAI);
  userUI.bind("rotation", userUI.handleOrientation);
  userUI.bind("resume", userUI.dismissExitModal);
  userUI.bind("saveExit", userUI.handleSaveAndExit);
}
