import { callbackResult, runCase } from './probe-runtime.js';
import { createCase as createAdvanced } from './cases/advanced-runtime.js';
import { createCase as createBase } from './cases/base-runtime.js';
import { createCase as createIdentity } from './cases/identity-privacy.js';
import { createCase as createNetwork } from './cases/network.js';
import { createCase as createTabPage } from './cases/tab-page.js';
import { createCase as createUserInterface } from './cases/user-interface.js';

const CASE_FACTORIES = Object.freeze({
  'advanced-runtime': createAdvanced,
  'base-runtime': createBase,
  'identity-privacy': createIdentity,
  network: createNetwork,
  'tab-page': createTabPage,
  'user-interface': createUserInterface,
});

const TIMEOUT_MS = 10_000;
const FIXTURE_URL = 'https://example.com/';

function failedResult(testCase, message) {
  return {
    caseId: String(testCase?.id ?? 'unknown'),
    context: String(testCase?.context ?? 'unknown'),
    detail: { kind: 'error', message: String(message), name: 'ProbeError' },
    durationMs: 0,
    member: String(testCase?.member ?? 'unknown'),
    namespace: String(testCase?.namespace ?? 'unknown'),
    status: 'FAILED',
  };
}

function notRunResult(testCase) {
  return {
    caseId: String(testCase.id),
    context: String(testCase.context),
    detail: { kind: 'gesture-required' },
    durationMs: 0,
    member: String(testCase.member),
    namespace: String(testCase.namespace),
    status: 'NOT_RUN',
  };
}

async function withTimeout(testCase, operation, timeoutMs) {
  let timer;
  try {
    return await Promise.race([
      operation(),
      new Promise((resolve) => {
        timer = setTimeout(
          () => resolve(failedResult(testCase, `probe timeout after ${timeoutMs}ms`)),
          timeoutMs,
        );
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

export async function runSequentially(testCases, executor, timeoutMs = TIMEOUT_MS) {
  const results = [];
  for (const testCase of testCases) {
    results.push(await withTimeout(testCase, () => executor(testCase), timeoutMs));
  }
  return results;
}

export function createProbeController({ descriptors, executeContextCase, executeWorkerCase }) {
  const pending = new Map();
  return {
    async handleMessage(message) {
      if (!message || (message.type !== 'six-probe-run' && message.type !== 'six-probe-result')) {
        return null;
      }
      if (message.type === 'six-probe-result') {
        const caseId = String(message.result?.caseId ?? '');
        if (!pending.has(caseId)) return null;
        pending.delete(caseId);
        return { caseId, type: 'six-probe-ack' };
      }

      const testCase = descriptors.get(String(message.caseId));
      if (!testCase) return failedResult({ id: message.caseId }, 'unknown probe case');
      if (testCase.context === 'user_gesture') {
        pending.set(testCase.id, testCase.context);
        return notRunResult(testCase);
      }
      if (testCase.context === 'service_worker') return executeWorkerCase(testCase);
      if (testCase.context === 'content_script' || testCase.context === 'offscreen_document') {
        const result = await executeContextCase(testCase);
        return result?.caseId === testCase.id ? result : failedResult(testCase, 'context result mismatch');
      }

      pending.set(testCase.id, testCase.context);
      return {
        caseId: testCase.id,
        context: testCase.context,
        type: 'six-probe-dispatch',
      };
    },
  };
}

async function dispatchContentCase(testCase, groupId) {
  let tabId;
  try {
    const tab = await callbackResult(
      globalThis,
      (done) => globalThis.chrome.tabs.create(
        { active: false, url: `${FIXTURE_URL}#sixbridge-api-probe=${encodeURIComponent(testCase.id)}` },
        done,
      ),
    );
    tabId = tab?.id;
    if (!Number.isInteger(tabId)) throw new Error('content fixture tab ID is missing');
    await globalThis.chrome.scripting.executeScript({
      files: ['content-probe.js'],
      target: { tabId },
    });
    const response = await callbackResult(
      globalThis,
      (done) => globalThis.chrome.tabs.sendMessage(
        tabId,
        { caseId: testCase.id, descriptor: testCase, groupId, type: 'six-probe-run' },
        done,
      ),
    );
    return response?.result;
  } finally {
    if (Number.isInteger(tabId)) {
      await callbackResult(globalThis, (done) => globalThis.chrome.tabs.remove(tabId, done));
    }
  }
}

async function dispatchOffscreenCase(testCase, groupId) {
  const existed = await globalThis.chrome.offscreen.hasDocument();
  try {
    if (!existed) {
      await globalThis.chrome.offscreen.createDocument({
        justification: 'SIX API capability probe context',
        reasons: ['DOM_PARSER'],
        url: 'offscreen-probe.html',
      });
    }
    const response = await callbackResult(
      globalThis,
      (done) => globalThis.chrome.runtime.sendMessage(
        {
          bridgeTarget: 'offscreen',
          caseId: testCase.id,
          descriptor: testCase,
          groupId,
          type: 'six-probe-run',
        },
        done,
      ),
    );
    return response?.result;
  } finally {
    if (!existed) await globalThis.chrome.offscreen.closeDocument();
  }
}

async function loadController() {
  const response = await fetch(globalThis.chrome.runtime.getURL('probe-cases.json'));
  if (!response.ok) throw new Error(`probe descriptor load failed: ${response.status}`);
  const bundle = await response.json();
  const factory = CASE_FACTORIES[bundle.groupId];
  if (!factory) throw new Error('unknown probe group');
  const descriptors = new Map(bundle.cases.map((testCase) => [testCase.id, testCase]));
  return createProbeController({
    descriptors,
    executeContextCase: (testCase) => (
      testCase.context === 'content_script'
        ? dispatchContentCase(testCase, bundle.groupId)
        : dispatchOffscreenCase(testCase, bundle.groupId)
    ),
    executeWorkerCase: (testCase) => runCase(factory(testCase, globalThis)),
  });
}

let controllerPromise;
if (globalThis.chrome?.runtime?.onMessage) {
  globalThis.chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (message?.bridgeTarget) return false;
    controllerPromise ??= loadController();
    controllerPromise
      .then((controller) => controller.handleMessage(message))
      .then(sendResponse, (error) => sendResponse(failedResult({ id: message?.caseId }, error?.message ?? error)));
    return true;
  });
}
