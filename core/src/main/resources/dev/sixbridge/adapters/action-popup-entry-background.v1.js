(() => {
  const messageType = "sixbridge:open-action-popup:v1";
  const popupPath = __SIXBRIDGE_POPUP_PATH_JSON__;

  chrome.runtime.onMessage.addListener((message) => {
    if (
      message === null ||
      typeof message !== "object" ||
      message.type !== messageType
    ) {
      return;
    }
    chrome.tabs.create({ url: chrome.runtime.getURL(popupPath) });
  });
})();
