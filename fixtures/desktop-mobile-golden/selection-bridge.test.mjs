import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const shimUrl = new URL(
  "../../core/src/main/resources/dev/sixbridge/adapters/desktop-selection-event-bridge.v1.js",
  import.meta.url,
);

async function createHarness({ selection = "selected", rangeKey = "range-1" } = {}) {
  const listeners = new Map();
  const dispatched = [];
  const frames = [];
  const rangeContainer = { isConnected: true };
  let currentSelection = selection;
  let currentRangeKey = rangeKey;
  const document = {
    addEventListener(type, listener, capture) {
      assert.equal(capture, true);
      listeners.set(type, listener);
    },
    dispatchEvent(event) {
      dispatched.push(event);
      listeners.get("mouseup")?.(event);
      return true;
    },
    getSelection() {
      return currentSelection
        ? {
            rangeCount: 1,
            toString: () => currentSelection,
            getRangeAt: () => ({
              startContainer: rangeContainer,
              startOffset: 0,
              endContainer: rangeContainer,
              endOffset: currentRangeKey.length,
              getBoundingClientRect: () => ({ left: 10, top: 20, width: 6, height: 8 }),
            }),
          }
        : { rangeCount: 0, toString: () => "" };
    },
  };
  const source = await readFile(shimUrl, "utf8");
  const sandbox = {
    document,
    MouseEvent: class MouseEvent {
      constructor(type, init) {
        this.type = type;
        Object.assign(this, init);
      }
    },
    requestAnimationFrame(callback) {
      frames.push(callback);
      return frames.length;
    },
  };
  vm.runInNewContext(source, sandbox, { filename: "desktop-selection-event-bridge.v1.js" });
  return {
    dispatched,
    emit(type, event = {}) {
      listeners.get(type)?.(event);
    },
    flushFrame() {
      frames.shift()?.();
    },
    flushFrames() {
      while (frames.length) frames.shift()();
    },
    setSelection(value, nextRangeKey = rangeKey) {
      currentSelection = value;
      currentRangeKey = nextRangeKey;
    },
  };
}

test("stable mobile selection forwards one synthetic mouseup with touch coordinates", async () => {
  const harness = await createHarness();

  harness.emit("touchend", { changedTouches: [{ clientX: 31, clientY: 47 }] });
  harness.emit("selectionchange");
  harness.flushFrames();

  assert.equal(harness.dispatched.length, 1);
  assert.equal(harness.dispatched[0].type, "mouseup");
  assert.equal(harness.dispatched[0].clientX, 31);
  assert.equal(harness.dispatched[0].clientY, 47);
  assert.equal(harness.dispatched[0].bubbles, true);
  harness.emit("selectionchange");
  harness.flushFrames();
  assert.equal(harness.dispatched.length, 1);
});

test("selection must remain stable across two animation frames", async () => {
  const harness = await createHarness();
  harness.emit("selectionchange");
  harness.flushFrame();
  harness.setSelection("changed", "range-2");
  harness.flushFrames();

  assert.equal(harness.dispatched.length, 0);
});

test("empty selection and actual mouseup suppress synthetic delivery", async () => {
  const empty = await createHarness({ selection: "" });
  empty.emit("pointerup", { clientX: 1, clientY: 2 });
  empty.flushFrames();
  assert.equal(empty.dispatched.length, 0);

  const actual = await createHarness();
  actual.emit("mouseup", { isTrusted: true });
  actual.emit("pointerup", { clientX: 1, clientY: 2 });
  actual.flushFrames();
  assert.equal(actual.dispatched.length, 0);
});
