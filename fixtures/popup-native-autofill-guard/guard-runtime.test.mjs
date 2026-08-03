import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const guardSource = await readFile(
  new URL(
    '../../core/src/main/resources/dev/sixbridge/adapters/popup-native-autofill-guard.v1.js',
    import.meta.url,
  ),
  'utf8',
);

class FakeInput {
  constructor(type, { valueGetterThrows = false } = {}) {
    this.attributes = new Map();
    if (type !== undefined) this.attributes.set('type', type);
    if (valueGetterThrows) {
      Object.defineProperty(this, 'value', {
        get() {
          throw new Error('input.value must not be read');
        },
      });
    }
  }

  getAttribute(name) {
    return this.attributes.get(name) ?? null;
  }

  setAttribute(name, value) {
    this.attributes.set(name, value);
  }

  querySelectorAll() {
    return [];
  }
}

function executeGuard({ initialInputs }) {
  let observer;
  let focusin;
  let fetchCalls = 0;
  let extensionApiReads = 0;
  const document = {
    querySelectorAll(selector) {
      assert.equal(selector, 'input');
      return initialInputs;
    },
  };
  class FakeMutationObserver {
    constructor(callback) {
      observer = callback;
    }

    observe(target, options) {
      assert.equal(target, document);
      assert.equal(options.childList, true);
      assert.equal(options.subtree, true);
      assert.deepEqual(Object.keys(options).sort(), ['childList', 'subtree']);
    }
  }
  const context = {
    HTMLInputElement: FakeInput,
    MutationObserver: FakeMutationObserver,
    document,
    addEventListener(type, listener, capture) {
      assert.equal(type, 'focusin');
      assert.equal(capture, true);
      focusin = listener;
    },
  };
  Object.defineProperties(context, {
    fetch: {
      get() {
        fetchCalls += 1;
        throw new Error('fetch must not be read');
      },
    },
    chrome: {
      get() {
        extensionApiReads += 1;
        throw new Error('chrome must not be read');
      },
    },
    browser: {
      get() {
        extensionApiReads += 1;
        throw new Error('browser must not be read');
      },
    },
  });
  vm.runInNewContext(guardSource, context, {
    filename: 'popup-native-autofill-guard.v1.js',
  });
  return {
    addNode(node) {
      observer([{ addedNodes: [node] }]);
    },
    focus(node) {
      focusin({ target: node });
    },
    get fetchCalls() {
      return fetchCalls;
    },
    get extensionApiReads() {
      return extensionApiReads;
    },
  };
}

test('대상 input만 off로 만들고 value와 extension API를 읽지 않는다', () => {
  const email = new FakeInput('email', { valueGetterThrows: true });
  const password = new FakeInput('password', { valueGetterThrows: true });
  const checkbox = new FakeInput('checkbox', { valueGetterThrows: true });
  const harness = executeGuard({ initialInputs: [email, password, checkbox] });

  assert.equal(email.getAttribute('autocomplete'), 'off');
  assert.equal(password.getAttribute('autocomplete'), 'off');
  assert.equal(checkbox.getAttribute('autocomplete'), null);
  assert.equal(harness.fetchCalls, 0);
  assert.equal(harness.extensionApiReads, 0);
});

test('MutationObserver와 capture focusin이 동적 input을 보호한다', () => {
  const harness = executeGuard({ initialInputs: [] });
  const dynamic = new FakeInput('url');

  harness.addNode(dynamic);
  harness.focus(dynamic);

  assert.equal(dynamic.getAttribute('autocomplete'), 'off');
});
