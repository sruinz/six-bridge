const hlsByTab = new Map();
chrome.webRequest.onBeforeRequest.addListener(
  ({ tabId, url }) => {
    if (tabId >= 0 && url.includes(".m3u8")) hlsByTab.set(tabId, [url]);
  },
  { urls: ["<all_urls>"] },
);
chrome.webNavigation?.onBeforeNavigate?.addListener(({ tabId }) => {
  hlsByTab.delete(tabId);
});
chrome.tabs.onRemoved.addListener((tabId) => hlsByTab.delete(tabId));
chrome.action.onClicked.addListener((tab) => {
  chrome.tabs.sendMessage(tab.id, {
    action: "findVideos",
    hlsUrls: hlsByTab.get(tab.id) || [],
  });
});
chrome.contextMenus.create({
  id: "download",
  title: "Download",
  contexts: ["link", "selection", "video"],
});
chrome.contextMenus.onClicked.addListener((info) => {
  chrome.storage.sync.get(["restUrl", "apiToken"], (settings) => {
    fetch(settings.restUrl, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${settings.apiToken}`,
      },
      body: JSON.stringify({
        url: info.linkUrl || info.srcUrl || info.selectionText,
        resolution: "best",
      }),
    });
  });
});
chrome.runtime.onMessage.addListener((message) => {
  if (message.action === "downloadVideo") {
    chrome.storage.sync.get(["restUrl"], () => {});
  }
});
