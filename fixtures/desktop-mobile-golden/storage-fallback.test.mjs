import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";

test("storage fallback golden uses local for executable access only", () => {
  const source = fs.readFileSync(
    new URL("./storage.js", import.meta.url),
    "utf8",
  );
  const calls = [];
  const context = {
    chrome: {
      storage: {
        local: {
          get(key) {
            calls.push(["get", key]);
          },
          set(value) {
            calls.push(["set", value]);
          },
        },
        sync: {
          get() {
            throw new Error("sync must not run");
          },
          set() {
            throw new Error("sync must not run");
          },
        },
      },
    },
    observedLiteral: null,
  };

  vm.runInNewContext(source, context);

  assert.equal(
    JSON.stringify(calls),
    JSON.stringify([
      ["get", "theme"],
      ["set", { mobile: true }],
    ]),
  );
  assert.equal(context.observedLiteral, "chrome.storage.sync");
  assert.match(source, /"chrome\.storage\.sync"/);
});
