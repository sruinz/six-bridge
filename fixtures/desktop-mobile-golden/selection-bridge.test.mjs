import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const fixtureManifestUrl = new URL("./manifest.json", import.meta.url);
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
  let syntheticMouseupHandler;
  const addListener = (type, listener) => {
    const handlers = listeners.get(type) ?? [];
    handlers.push(listener);
    listeners.set(type, handlers);
  };
  const emit = (type, event = {}) => {
    for (const listener of listeners.get(type) ?? []) listener(event);
  };
  const document = {
    addEventListener(type, listener, capture) {
      assert.equal(capture, true);
      addListener(type, listener);
    },
    dispatchEvent(event) {
      dispatched.push(event);
      emit("mouseup", event);
      syntheticMouseupHandler?.(event);
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
    WeakMap,
    WeakSet,
    requestAnimationFrame(callback) {
      frames.push(callback);
      return frames.length;
    },
  };
  vm.runInNewContext(source, sandbox, { filename: "desktop-selection-event-bridge.v1.js" });
  return {
    dispatched,
    emit,
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
    setSyntheticMouseupHandler(handler) {
      syntheticMouseupHandler = handler;
    },
  };
}

test("golden manifest references only existing fixture scripts", async () => {
  const manifest = JSON.parse(await readFile(fixtureManifestUrl, "utf8"));
  const scripts = manifest.content_scripts.flatMap((block) => block.js ?? []);

  await Promise.all(scripts.map((script) => readFile(new URL(`./${script}`, fixtureManifestUrl))));
});

test("stable mobile selection forwards one synthetic mouseup with touch coordinates", async () => {
  const harness = await createHarness();

  harness.emit("touchstart", { changedTouches: [{ clientX: 31, clientY: 47 }] });
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

test("new selection signals during pending frames are rescheduled until stable", async () => {
  const harness = await createHarness({ selection: "first" });
  harness.emit("pointerdown", { clientX: 1, clientY: 2 });
  harness.emit("pointerup", { clientX: 1, clientY: 2 });
  harness.flushFrame();
  harness.setSelection("final", "range-final");
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
  empty.emit("pointerdown", { clientX: 1, clientY: 2 });
  empty.emit("pointerup", { clientX: 1, clientY: 2 });
  empty.flushFrames();
  assert.equal(empty.dispatched.length, 0);

  const actual = await createHarness();
  actual.emit("pointerdown", { clientX: 1, clientY: 2 });
  actual.emit("mouseup", { isTrusted: true });
  actual.emit("pointerup", { clientX: 1, clientY: 2 });
  actual.flushFrames();
  assert.equal(actual.dispatched.length, 0);
});

test("a new gesture can deliver the same selection again", async () => {
  const harness = await createHarness();
  harness.emit("pointerdown", { clientX: 1, clientY: 2 });
  harness.emit("pointerup", { clientX: 1, clientY: 2 });
  harness.flushFrames();
  harness.emit("pointerdown", { clientX: 1, clientY: 2 });
  harness.emit("pointerup", { clientX: 1, clientY: 2 });
  harness.flushFrames();

  assert.equal(harness.dispatched.length, 2);
});

test("synthetic mouseup cannot loop when a handler changes selection", async () => {
  const harness = await createHarness();
  harness.setSyntheticMouseupHandler(() => {
    harness.setSelection("alternated", "range-alternated");
    harness.emit("selectionchange");
  });
  harness.emit("pointerdown", { clientX: 1, clientY: 2 });
  harness.emit("pointerup", { clientX: 1, clientY: 2 });
  harness.flushFrames();

  assert.equal(harness.dispatched.length, 1);
});

test("shim keeps no raw selection text or live Range in persistent state", async () => {
  const source = await readFile(shimUrl, "utf8");

  assert.equal(source.includes("return { text"), false);
  assert.equal(source.includes("range: snapshot.range"), false);
  assert.equal(source.includes("lastActual"), false);
});
