import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const contentUrl = new URL(
  "../../core/src/main/resources/dev/sixbridge/adapters/action-popup-entry-content.v1.js",
  import.meta.url,
);
const backgroundUrl = new URL(
  "../../core/src/main/resources/dev/sixbridge/adapters/action-popup-entry-background.v1.js",
  import.meta.url,
);

test("content entry uses a closed shadow root and messages only for a trusted click", async () => {
  const messages = [];
  const appended = [];
  const shadowChildren = [];
  let clickListener;
  const host = {
    shadowRoot: null,
    attachShadow({ mode }) {
      assert.equal(mode, "closed");
      return {
        append(...children) {
          shadowChildren.push(...children);
        },
      };
    },
  };
  const button = {
    attributes: new Map(),
    addEventListener(type, listener) {
      assert.equal(type, "click");
      clickListener = listener;
    },
    setAttribute(name, value) {
      this.attributes.set(name, value);
    },
  };
  const style = {};
  const document = {
    documentElement: {
      append(child) {
        appended.push(child);
      },
    },
    createElement(tag) {
      if (tag === "div") return host;
      if (tag === "button") return button;
      if (tag === "style") return style;
      assert.fail(`unexpected element: ${tag}`);
    },
  };
  const chrome = {
    runtime: {
      getManifest: () => ({ name: "Selection fixture" }),
      sendMessage(message) {
        messages.push(message);
      },
    },
  };
  const sandbox = { chrome, document };

  vm.runInNewContext(await readFile(contentUrl, "utf8"), sandbox);

  assert.equal(messages.length, 0);
  assert.equal(appended[0], host);
  assert.equal(host.shadowRoot, null);
  assert.equal(shadowChildren.includes(button), true);
  assert.equal(button.textContent, "확장 열기");
  assert.equal(button.attributes.get("aria-label"), "Selection fixture 확장 열기");
  clickListener({ isTrusted: false });
  assert.equal(messages.length, 0);
  clickListener({ isTrusted: true });
  assert.equal(messages.length, 1);
  assert.equal(messages[0].type, "sixbridge:open-action-popup:v1");
});

test("content entry exits without DOM or messaging work in a child frame", async () => {
  let documentReads = 0;
  let runtimeReads = 0;
  const document = new Proxy(
    {},
    {
      get() {
        documentReads += 1;
        throw new Error("child frame must not read document");
      },
    },
  );
  const chrome = {
    get runtime() {
      runtimeReads += 1;
      throw new Error("child frame must not read runtime");
    },
  };

  vm.runInNewContext(await readFile(contentUrl, "utf8"), {
    chrome,
    document,
    self: {},
    top: {},
  });

  assert.equal(documentReads, 0);
  assert.equal(runtimeReads, 0);
});

test("background opens only the fixed message at the generated extension URL", async () => {
  let listener;
  const created = [];
  const chrome = {
    runtime: {
      getURL: (path) => `chrome-extension://fixture/${path}`,
      onMessage: {
        addListener(value) {
          listener = value;
        },
      },
    },
    tabs: {
      create(options) {
        created.push(options);
      },
    },
  };
  const template = await readFile(backgroundUrl, "utf8");
  const source = template.replace("__SIXBRIDGE_POPUP_PATH_JSON__", '"popup.html"');
  vm.runInNewContext(source, { chrome });

  listener({ type: "other" });
  assert.equal(created.length, 0);
  listener({ type: "sixbridge:open-action-popup:v1" });
  assert.equal(created.length, 1);
  assert.equal(created[0].url, "chrome-extension://fixture/popup.html");
});
