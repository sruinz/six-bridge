const { runtime } = chrome;

document.addEventListener("mouseup", (event) => {
  const selection = window.getSelection();
  if (selection && selection.toString()) {
    runtime.sendMessage({
      type: "smart-context-selection",
      selectedText: selection.toString()
    });
  }
});
