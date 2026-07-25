chrome.runtime.onMessage.addListener((message) => {
  if (message.action === "findVideos") {
    chrome.runtime.sendMessage({
      action: "downloadVideo",
      url: message.hlsUrls[0] || location.href,
    });
  }
});
