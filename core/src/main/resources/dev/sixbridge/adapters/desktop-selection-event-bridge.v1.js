(() => {
  const syntheticEvents = new WeakSet();
  let lastPoint;
  let lastActual;
  let lastDelivered;
  let pending = false;

  const selectionSnapshot = () => {
    const selection = document.getSelection();
    const text = selection?.toString();
    if (!text || selection.rangeCount !== 1) return null;
    const range = selection.getRangeAt(0);
    if (!range?.startContainer?.isConnected || !range.endContainer?.isConnected) return null;
    return { text, fingerprint: fingerprint(text), range };
  };

  const fingerprint = (text) => {
    let value = text.length;
    for (let index = 0; index < text.length; index += 1) {
      value = ((value * 31) + text.charCodeAt(index)) | 0;
    }
    return value;
  };

  const sameSelection = (first, second) =>
    first && second &&
    first.text === second.text &&
    first.range.startContainer === second.range.startContainer &&
    first.range.startOffset === second.range.startOffset &&
    first.range.endContainer === second.range.endContainer &&
    first.range.endOffset === second.range.endOffset;

  const rememberedSelection = (snapshot) => snapshot && ({
    fingerprint: snapshot.fingerprint,
    range: snapshot.range,
  });

  const sameRememberedSelection = (remembered, snapshot) =>
    remembered && snapshot &&
    remembered.fingerprint === snapshot.fingerprint &&
    remembered.range.startContainer === snapshot.range.startContainer &&
    remembered.range.startOffset === snapshot.range.startOffset &&
    remembered.range.endContainer === snapshot.range.endContainer &&
    remembered.range.endOffset === snapshot.range.endOffset;

  const rememberPoint = (event) => {
    if (Number.isFinite(event.clientX) && Number.isFinite(event.clientY)) {
      lastPoint = { x: event.clientX, y: event.clientY };
    }
  };

  const pointFor = (range) => {
    if (lastPoint) return lastPoint;
    const rect = range.getBoundingClientRect();
    return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
  };

  const scheduleDelivery = () => {
    if (pending) return;
    pending = true;
    requestAnimationFrame(() => {
      const first = selectionSnapshot();
      requestAnimationFrame(() => {
        pending = false;
        const second = selectionSnapshot();
        if (!sameSelection(first, second) ||
            sameRememberedSelection(lastActual, second) ||
            sameRememberedSelection(lastDelivered, second)) {
          return;
        }
        const point = pointFor(second.range);
        const event = new MouseEvent("mouseup", {
          bubbles: true,
          cancelable: true,
          composed: true,
          clientX: point.x,
          clientY: point.y,
        });
        syntheticEvents.add(event);
        lastDelivered = rememberedSelection(second);
        document.dispatchEvent(event);
      });
    });
  };

  document.addEventListener("pointerup", (event) => {
    rememberPoint(event);
    scheduleDelivery();
  }, true);
  document.addEventListener("touchend", (event) => {
    rememberPoint(event.changedTouches?.[0] ?? {});
    scheduleDelivery();
  }, true);
  document.addEventListener("selectionchange", scheduleDelivery, true);
  document.addEventListener("mouseup", (event) => {
    if (syntheticEvents.has(event)) return;
    if (event.isTrusted) {
      lastActual = rememberedSelection(selectionSnapshot());
      lastDelivered = lastActual;
    }
  }, true);
})();
