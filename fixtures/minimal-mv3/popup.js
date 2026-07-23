chrome.storage.local.get("sixBridgeGateInstalled", ({ sixBridgeGateInstalled }) => {
  document.querySelector("#status").textContent = sixBridgeGateInstalled
    ? "래퍼와 MV3 런타임이 동작합니다."
    : "백그라운드 초기화를 기다리는 중입니다.";
});
