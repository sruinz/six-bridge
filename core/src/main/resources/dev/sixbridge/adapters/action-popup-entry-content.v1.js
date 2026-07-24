(() => {
  const installedKey = "__sixbridgeActionPopupEntryV1";
  if (globalThis[installedKey]) return;
  globalThis[installedKey] = true;

  const host = document.createElement("div");
  const shadow = host.attachShadow({ mode: "closed" });
  const style = document.createElement("style");
  style.textContent = `
    :host {
      all: initial;
      position: fixed;
      right: 8px;
      top: 50%;
      z-index: 2147483647;
      transform: translateY(-50%);
    }
    button {
      appearance: none;
      border: 0;
      border-radius: 999px;
      background: #1f2937;
      color: #fff;
      cursor: pointer;
      font: 600 12px/1.2 system-ui, sans-serif;
      padding: 9px 11px;
      box-shadow: 0 2px 8px rgb(0 0 0 / 28%);
    }
  `;
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = "확장 열기";
  button.setAttribute(
    "aria-label",
    `${chrome.runtime.getManifest().name} 확장 열기`,
  );
  button.addEventListener("click", (event) => {
    if (!event.isTrusted) return;
    chrome.runtime.sendMessage({ type: "sixbridge:open-action-popup:v1" });
  });
  shadow.append(style, button);
  document.documentElement.append(host);
})();
