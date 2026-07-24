const SIX_BRIDGE_GATE_PROBE = "sixbridge-gate-probe";

chrome.runtime.onInstalled.addListener(() => {
  chrome.storage.local.set({
    sixBridgeGateInstalled: true,
    installedAt: new Date().toISOString(),
  });
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type !== SIX_BRIDGE_GATE_PROBE) {
    return false;
  }

  chrome.storage.local.get(
    "sixBridgeGateInstalled",
    ({ sixBridgeGateInstalled }) => {
      sendResponse({ ok: sixBridgeGateInstalled === true });
    },
  );
  return true;
});
