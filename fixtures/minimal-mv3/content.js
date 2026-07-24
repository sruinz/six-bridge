(() => {
  const probeType = "sixbridge-gate-probe";
  const markerKey = "sixBridgeGate";
  const hostId = "six-bridge-runtime-gate";
  const displayMilliseconds = 8000;
  const root = document.documentElement;

  if (!root || root.dataset[markerKey]) {
    return;
  }
  root.dataset[markerKey] = "probing";

  const fail = (reason) => {
    root.dataset[markerKey] = "failed";
    console.warn("[SIX Bridge] runtime probe failed", reason);
  };

  const showBadge = () => {
    if (document.getElementById(hostId)) {
      return;
    }

    const host = document.createElement("div");
    host.id = hostId;
    Object.assign(host.style, {
      all: "initial",
      position: "fixed",
      right: "16px",
      bottom: "16px",
      zIndex: "2147483647",
      pointerEvents: "none",
    });

    const shadow = host.attachShadow({ mode: "closed" });
    const style = document.createElement("style");
    style.textContent = `
      .badge {
        padding: 10px 14px;
        border: 1px solid rgba(255, 255, 255, 0.28);
        border-radius: 999px;
        color: #fff;
        background: #1261c9;
        box-shadow: 0 6px 24px rgba(0, 0, 0, 0.28);
        font: 700 14px/1 system-ui, sans-serif;
        letter-spacing: 0.02em;
      }
    `;
    const badge = document.createElement("div");
    badge.className = "badge";
    badge.textContent = "SIX Bridge OK";
    shadow.append(style, badge);
    root.append(host);
    setTimeout(() => host.remove(), displayMilliseconds);
  };

  try {
    chrome.runtime.sendMessage({ type: probeType }, (response) => {
      if (chrome.runtime.lastError) {
        fail(chrome.runtime.lastError.message);
        return;
      }
      if (response?.ok !== true) {
        fail("storage flag missing");
        return;
      }

      try {
        showBadge();
        root.dataset[markerKey] = "ok";
      } catch (error) {
        fail(error instanceof Error ? error.message : String(error));
      }
    });
  } catch (error) {
    fail(error instanceof Error ? error.message : String(error));
  }
})();
