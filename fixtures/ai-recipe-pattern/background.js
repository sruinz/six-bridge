chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: "fixture-action",
    title: "Fixture action",
    contexts: ["link"],
  });
});

chrome.contextMenus.onClicked.addListener((info) => {
  chrome.storage.sync.get(["baseUrl"], ({ baseUrl }) => {
    chrome.tabs.create({ url: `${baseUrl}?target=${encodeURIComponent(info.linkUrl)}` });
  });
});
