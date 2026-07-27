const { runtime } = chrome;

document.addEventListener("mouseup", (event) => {
  const selection = window.getSelection();
  if (selection && selection.toString()) {
    runtime.sendMessage({ selection: selection.toString() });
  }
});
