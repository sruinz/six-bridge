import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const contentUrl = new URL("./content.js", import.meta.url);
const manifestUrl = new URL("./manifest.json", import.meta.url);

function createElement(tagName) {
  const element = {
    tagName,
    id: "",
    textContent: "",
    style: {},
    children: [],
    removed: false,
    append(...children) {
      this.children.push(...children);
    },
    attachShadow({ mode }) {
      this.shadowMode = mode;
      this.shadowRootForTest = {
        children: [],
        append(...children) {
          this.children.push(...children);
        },
      };
      return this.shadowRootForTest;
    },
    remove() {
      this.removed = true;
    },
  };
  return element;
}

async function createHarness({ response = { ok: true }, lastError } = {}) {
  const hosts = [];
  const messages = [];
  const timers = [];
  const warnings = [];
  const documentElement = {
    dataset: {},
    append(element) {
      hosts.push(element);
    },
  };
  const document = {
    documentElement,
    createElement,
    getElementById(id) {
      return hosts.find((host) => host.id === id && !host.removed) ?? null;
    },
  };
  const chrome = {
    runtime: {
      lastError,
      sendMessage(message, callback) {
        messages.push(message);
        callback(response);
      },
    },
  };
  const sandbox = {
    chrome,
    console: {
      warn(...values) {
        warnings.push(values);
      },
    },
    document,
    setTimeout(callback, delay) {
      timers.push({ callback, delay });
      return timers.length;
    },
  };
  const source = await readFile(contentUrl, "utf8");

  return {
    documentElement,
    hosts,
    messages,
    run() {
      vm.runInNewContext(source, sandbox, { filename: "content.js" });
    },
    timers,
    warnings,
  };
}

test("ok 응답 뒤 닫힌 Shadow DOM 배지를 삽입한다", async () => {
  const harness = await createHarness();

  harness.run();

  assert.equal(harness.messages.length, 1);
  assert.equal(harness.messages[0].type, "sixbridge-gate-probe");
  assert.equal(harness.hosts.length, 1);
  const host = harness.hosts[0];
  assert.equal(host.id, "six-bridge-runtime-gate");
  assert.equal(host.shadowMode, "closed");
  assert.equal(
    host.shadowRootForTest.children.some(
      (child) => child.textContent === "SIX Bridge OK",
    ),
    true,
  );
  assert.equal(harness.documentElement.dataset.sixBridgeGate, "ok");
});

test("같은 문서에서 재실행해도 메시지와 배지를 중복 생성하지 않는다", async () => {
  const harness = await createHarness();

  harness.run();
  harness.run();

  assert.equal(harness.messages.length, 1);
  assert.equal(harness.hosts.length, 1);
});

test("배지 host를 정확히 8초 뒤 제거한다", async () => {
  const harness = await createHarness();

  harness.run();

  assert.equal(harness.timers.length, 1);
  assert.equal(harness.timers[0].delay, 8000);
  harness.timers[0].callback();
  assert.equal(harness.hosts[0].removed, true);
});

test("probe 실패 시 배지를 표시하지 않고 경고를 한 번만 남긴다", async () => {
  const harness = await createHarness({ response: { ok: false } });

  harness.run();
  harness.run();

  assert.equal(harness.hosts.length, 0);
  assert.equal(harness.warnings.length, 1);
  assert.equal(harness.documentElement.dataset.sixBridgeGate, "failed");
});

test("manifest는 1.0.1과 storage 전용 권한을 유지한다", async () => {
  const manifest = JSON.parse(await readFile(manifestUrl, "utf8"));

  assert.equal(manifest.version, "1.0.1");
  assert.deepEqual(manifest.permissions, ["storage"]);
  assert.equal(manifest.background.service_worker, "background.js");
  assert.equal(manifest.action.default_popup, "popup.html");
  assert.deepEqual(manifest.content_scripts[0].matches, ["http://*/*", "https://*/*"]);
  assert.equal(manifest.content_scripts[0].run_at, "document_idle");
});
