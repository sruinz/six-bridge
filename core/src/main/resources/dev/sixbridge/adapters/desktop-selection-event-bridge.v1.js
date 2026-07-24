(() => {
  const syntheticEvents = new WeakSet();
  const nodeIds = new WeakMap();
  let nextNodeId = 1;
  let gesture = 0;
  let deliveredGesture = -1;
  let actualMouseup;
  let lastPoint;
  let generation = 0;
  let scheduled = false;
  let dispatching = false;

  const fingerprint = (value) => {
    let result = value.length;
    for (let index = 0; index < value.length; index += 1) {
      result = ((result * 31) + value.charCodeAt(index)) | 0;
    }
    return result;
  };

  const nodeId = (node) => {
    let id = nodeIds.get(node);
    if (!id) {
      id = nextNodeId;
      nextNodeId += 1;
      nodeIds.set(node, id);
    }
    return id;
  };

  const selectionSnapshot = () => {
    const selection = document.getSelection();
    if (!selection || selection.rangeCount !== 1) return null;
    const value = selection.toString();
    if (!value) return null;
    const range = selection.getRangeAt(0);
    if (!range?.startContainer?.isConnected || !range.endContainer?.isConnected) return null;
    const rect = range.getBoundingClientRect();
    return {
      fingerprint: fingerprint(value),
      startNode: nodeId(range.startContainer),
      startOffset: range.startOffset,
      endNode: nodeId(range.endContainer),
      endOffset: range.endOffset,
      x: rect.left + rect.width / 2,
      y: rect.top + rect.height / 2,
    };
  };

  const sameSelection = (first, second) =>
    first && second &&
    first.fingerprint === second.fingerprint &&
    first.startNode === second.startNode &&
    first.startOffset === second.startOffset &&
    first.endNode === second.endNode &&
    first.endOffset === second.endOffset;

  const rememberPoint = (event) => {
    if (Number.isFinite(event.clientX) && Number.isFinite(event.clientY)) {
      lastPoint = { x: event.clientX, y: event.clientY };
    }
  };

  const beginGesture = () => {
    gesture += 1;
    actualMouseup = undefined;
    lastPoint = undefined;
    generation += 1;
  };

  const queueStableCheck = () => {
    if (scheduled || dispatching || deliveredGesture === gesture) return;
    scheduled = true;
    const queuedGesture = gesture;
    requestAnimationFrame(() => {
      const first = selectionSnapshot();
      const firstGeneration = generation;
      requestAnimationFrame(() => {
        scheduled = false;
        const second = selectionSnapshot();
        if (queuedGesture !== gesture || firstGeneration !== generation) {
          queueStableCheck();
          return;
        }
        if (!first || !second || !sameSelection(first, second) ||
            (actualMouseup?.gesture === gesture && sameSelection(actualMouseup.selection, second)) ||
            deliveredGesture === gesture) {
          return;
        }
        const point = lastPoint ?? { x: second.x, y: second.y };
        const event = new MouseEvent("mouseup", {
          bubbles: true,
          cancelable: true,
          composed: true,
          clientX: point.x,
          clientY: point.y,
        });
        deliveredGesture = gesture;
        dispatching = true;
        syntheticEvents.add(event);
        document.dispatchEvent(event);
        dispatching = false;
      });
    });
  };

  const selectionSignal = () => {
    if (!selectionSnapshot()) {
      beginGesture();
      return;
    }
    generation += 1;
    queueStableCheck();
  };

  document.addEventListener("pointerdown", (event) => {
    beginGesture();
    rememberPoint(event);
  }, true);
  document.addEventListener("touchstart", (event) => {
    beginGesture();
    rememberPoint(event.changedTouches?.[0] ?? {});
  }, true);
  document.addEventListener("pointerup", (event) => {
    rememberPoint(event);
    selectionSignal();
  }, true);
  document.addEventListener("touchend", (event) => {
    rememberPoint(event.changedTouches?.[0] ?? {});
    selectionSignal();
  }, true);
  document.addEventListener("selectionchange", selectionSignal, true);
  document.addEventListener("mouseup", (event) => {
    if (syntheticEvents.has(event) || !event.isTrusted) return;
    const selection = selectionSnapshot();
    if (!selection) {
      beginGesture();
      return;
    }
    actualMouseup = { gesture, selection };
  }, true);
})();
