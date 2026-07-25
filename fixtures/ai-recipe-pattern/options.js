chrome.storage.sync.get(["baseUrl"], ({ baseUrl }) => {
  document.querySelector("#base-url").value = baseUrl ?? "";
});

document.querySelector("#settings").addEventListener("submit", (event) => {
  event.preventDefault();
  chrome.storage.sync.set({
    baseUrl: document.querySelector("#base-url").value,
  });
});
