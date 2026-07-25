chrome.storage.sync.get(["configUrl"], () => {});
document.querySelector("form").addEventListener("submit", (event) => {
  event.preventDefault();
  chrome.storage.sync.set({ configUrl: "http://fixture.invalid#token" });
});
