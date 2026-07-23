import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const backgroundUrl = new URL("./background.js", import.meta.url);

async function createHarness({ installed = true } = {}) {
  let installedListener;
  let messageListener;
  const writes = [];
  let readCount = 0;

  const chrome = {
    runtime: {
      onInstalled: {
        addListener(listener) {
          installedListener = listener;
        },
      },
      onMessage: {
        addListener(listener) {
          messageListener = listener;
        },
      },
    },
    storage: {
      local: {
        set(value) {
          writes.push(value);
        },
        get(key, callback) {
          assert.equal(key, "sixBridgeGateInstalled");
          readCount += 1;
          callback({ sixBridgeGateInstalled: installed });
        },
      },
    },
  };

  const source = await readFile(backgroundUrl, "utf8");
  vm.runInNewContext(source, { chrome, Date }, { filename: "background.js" });

  return {
    get installedListener() {
      return installedListener;
    },
    get messageListener() {
      return messageListener;
    },
    get readCount() {
      return readCount;
    },
    writes,
  };
}

test("설치 시 storage 플래그를 기록한다", async () => {
  const harness = await createHarness();
  assert.equal(typeof harness.installedListener, "function");

  harness.installedListener();

  assert.equal(harness.writes.length, 1);
  assert.equal(harness.writes[0].sixBridgeGateInstalled, true);
  assert.equal(typeof harness.writes[0].installedAt, "string");
});

test("probe 메시지에 storage 상태를 비동기로 응답한다", async () => {
  const harness = await createHarness({ installed: true });
  let response;

  const keepChannel = harness.messageListener(
    { type: "sixbridge-gate-probe" },
    {},
    (value) => {
      response = value;
    },
  );

  assert.equal(keepChannel, true);
  assert.equal(response.ok, true);
  assert.equal(harness.readCount, 1);
});

test("storage 플래그가 없으면 ok=false를 응답한다", async () => {
  const harness = await createHarness({ installed: false });
  let response;

  harness.messageListener(
    { type: "sixbridge-gate-probe" },
    {},
    (value) => {
      response = value;
    },
  );

  assert.equal(response.ok, false);
});

test("다른 메시지는 처리하지 않는다", async () => {
  const harness = await createHarness();

  const keepChannel = harness.messageListener(
    { type: "unrelated" },
    {},
    () => assert.fail("응답하면 안 됩니다."),
  );

  assert.equal(keepChannel, false);
  assert.equal(harness.readCount, 0);
});
