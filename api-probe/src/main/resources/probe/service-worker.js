import {
  callbackResult,
  failedResult,
  notRunResult,
  ProbeTimeoutError,
  runCase,
  validateResultEnvelope,
  withDeadline,
} from './probe-runtime.js';
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
const DEFAULT_TIMEOUT_MS = 10_000;
const CLEANUP_TIMEOUT_MS = 1_000;
const FIXTURE_URL = 'https://example.com/';
const FIXTURE_ORIGIN = new URL(FIXTURE_URL).origin;

function randomRunToken(environment) {
  const bytes = new Uint8Array(16);
  environment.crypto.getRandomValues(bytes);
  return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
}

function senderIdentity(sender) {
  let origin = '';
  try {
    origin = new URL(String(sender?.url ?? '')).origin;
  } catch {
    origin = '';
  }
  return {
    id: String(sender?.id ?? ''),
    origin,
    tabId: Number.isInteger(sender?.tab?.id) ? sender.tab.id : null,
    url: String(sender?.url ?? ''),
  };
}

function sameSender(expected, sender) {
  const actual = senderIdentity(sender);
  return expected.id === actual.id
    && (!expected.url || expected.url === actual.url)
    && (!expected.origin || expected.origin === actual.origin)
    && (expected.tabId === null || expected.tabId === actual.tabId);
}

function resultEnvelope(value) {
  return { result: value, type: 'six-probe-result' };
}

function dispatchEnvelope(testCase, runToken, initialResult) {
  return {
    caseId: testCase.id,
    context: testCase.context,
    initialResult,
    runToken,
    type: 'six-probe-dispatch',
  };
}

export function createProbeController({
  descriptors,
  executeContextCase,
  executeWorkerCase,
  extensionPageUrl,
  runtimeId,
  timeoutMs = DEFAULT_TIMEOUT_MS,
  tokenFactory,
}) {
  const activeCases = new Set();
  const pending = new Map();
  const makeToken = tokenFactory ?? (() => randomRunToken(globalThis));
  const pendingKey = (caseId, runToken) => `${caseId}\u0000${runToken}`;

  const deletePending = (record, releaseActive = true) => {
    clearTimeout(record.timer);
    pending.delete(pendingKey(record.testCase.id, record.runToken));
    if (releaseActive) activeCases.delete(record.testCase.id);
  };

  const createPending = (testCase, runToken, expectedSender) => {
    let resolveResult;
    const resultPromise = new Promise((resolve) => { resolveResult = resolve; });
    const record = {
      expectedSender,
      resolveResult,
      resultPromise,
      runToken,
      testCase,
      timer: null,
    };
    record.timer = setTimeout(
      () => deletePending(
        record,
        testCase.context === 'extension_page' || testCase.context === 'user_gesture',
      ),
      timeoutMs,
    );
    pending.set(pendingKey(testCase.id, runToken), record);
    return record;
  };

  const validPageSender = (sender) => sameSender(
    { id: runtimeId, origin: '', tabId: null, url: extensionPageUrl },
    sender,
  );

  return {
    async handleMessage(message, sender) {
      if (!message || (message.type !== 'six-probe-run' && message.type !== 'six-probe-result')) {
        return null;
      }
      if (message.type === 'six-probe-result') {
        const caseId = String(message.result?.caseId ?? '');
        const runToken = String(message.result?.runToken ?? '');
        const record = pending.get(pendingKey(caseId, runToken));
        if (!record || !sameSender(record.expectedSender, sender)) return null;
        let validated;
        try {
          validated = validateResultEnvelope(message, record.testCase, record.runToken);
        } catch {
          return null;
        }
        deletePending(
          record,
          record.testCase.context === 'extension_page'
            || record.testCase.context === 'user_gesture',
        );
        record.resolveResult(validated);
        return { caseId, runToken, type: 'six-probe-ack' };
      }

      const testCase = descriptors.get(String(message.caseId));
      const runToken = makeToken();
      if (!testCase) return resultEnvelope(failedResult(
        { context: 'unknown', id: String(message.caseId), member: 'unknown', namespace: 'unknown' },
        runToken,
        new Error('unknown probe case'),
      ));
      if (!validPageSender(sender)) {
        return resultEnvelope(failedResult(testCase, runToken, new Error('untrusted probe sender')));
      }
      if (activeCases.has(testCase.id)) {
        return resultEnvelope(failedResult(testCase, runToken, new Error('probe case is already running')));
      }
      activeCases.add(testCase.id);

      if (testCase.context === 'service_worker') {
        try {
          const value = await executeWorkerCase(testCase, runToken, timeoutMs);
          return validateResultEnvelope(resultEnvelope(value), testCase, runToken);
        } catch (error) {
          return resultEnvelope(failedResult(testCase, runToken, error, timeoutMs));
        } finally {
          activeCases.delete(testCase.id);
        }
      }

      const pageSender = senderIdentity(sender);
      const record = createPending(testCase, runToken, pageSender);
      if (testCase.context === 'extension_page' || testCase.context === 'user_gesture') {
        return dispatchEnvelope(
          testCase,
          runToken,
          testCase.context === 'user_gesture' ? notRunResult(testCase, runToken) : null,
        );
      }
      if (testCase.context !== 'content_script' && testCase.context !== 'offscreen_document') {
        deletePending(record);
        return resultEnvelope(failedResult(testCase, runToken, new Error('unsupported probe context')));
      }

      try {
        return await executeContextCase(testCase, runToken, record, timeoutMs);
      } catch (error) {
        return resultEnvelope(failedResult(testCase, runToken, error, timeoutMs));
      } finally {
        deletePending(record);
      }
    },
    pendingCount() {
      return pending.size;
    },
  };
}

async function cleanupFixtureTabs(environment, markerUrl, knownTabId) {
  const cleanupDeadline = Date.now() + CLEANUP_TIMEOUT_MS;
  const cleanupRemaining = () => Math.max(1, cleanupDeadline - Date.now());
  let ids = Number.isInteger(knownTabId) ? [knownTabId] : [];
  if (ids.length === 0) {
    try {
      const tabs = await callbackResult(
        environment,
        (done) => environment.chrome.tabs.query({ url: `${FIXTURE_URL}*` }, done),
        cleanupRemaining(),
      );
      ids = (tabs ?? []).filter((tab) => tab.url === markerUrl && Number.isInteger(tab.id)).map((tab) => tab.id);
    } catch {
      ids = [];
    }
  }
  for (const id of ids) {
    try {
      await callbackResult(
        environment,
        (done) => environment.chrome.tabs.remove(id, done),
        cleanupRemaining(),
      );
    } catch {
      // The owned tab is already gone or the browser rejected cleanup.
    }
  }
}

async function dispatchContentCase(environment, testCase, groupId, runToken, record, timeoutMs) {
  const marker = `sixbridge-api-probe=${encodeURIComponent(testCase.id)}&runToken=${encodeURIComponent(runToken)}`;
  const markerUrl = `${FIXTURE_URL}#${marker}`;
  let tabId;
  try {
    return await withDeadline(async () => {
      const tab = await callbackResult(
        environment,
        (done) => environment.chrome.tabs.create({ active: false, url: markerUrl }, done),
        timeoutMs,
      );
      tabId = tab?.id;
      if (!Number.isInteger(tabId)) throw new Error('content fixture tab ID is missing');
      record.expectedSender = { id: environment.chrome.runtime.id, origin: FIXTURE_ORIGIN, tabId, url: '' };
      await environment.chrome.scripting.executeScript({ files: ['content-probe.js'], target: { tabId } });
      const dispatched = await callbackResult(
        environment,
        (done) => environment.chrome.tabs.sendMessage(
          tabId,
          { caseId: testCase.id, descriptor: testCase, groupId, runToken, type: 'six-probe-run' },
          done,
        ),
        timeoutMs,
      );
      if (
        dispatched?.type !== 'six-probe-dispatched'
        || dispatched.caseId !== testCase.id
        || dispatched.runToken !== runToken
      ) {
        throw new Error('content probe dispatch response mismatch');
      }
      return record.resultPromise;
    }, timeoutMs);
  } finally {
    await cleanupFixtureTabs(environment, markerUrl, tabId);
  }
}

function createOffscreenLeaseManager(environment, requestedLifecycleTimeoutMs) {
  const lifecycleTimeoutMs = Number.isInteger(requestedLifecycleTimeoutMs)
    ? Math.max(1, Math.min(requestedLifecycleTimeoutMs, 10_000))
    : CLEANUP_TIMEOUT_MS;
  let leaseCount = 0;
  let ownedByProbe = false;
  let closeAttempt = null;
  let lifecycleSequence = 0;
  let queue = Promise.resolve();

  const serialize = (operation) => {
    const result = queue.then(operation, operation);
    queue = result.catch(() => {});
    return result;
  };

  const boundedTimeout = (deadlineAt) => Math.max(
    1,
    Math.min(lifecycleTimeoutMs, deadlineAt - Date.now()),
  );

  const beginClose = async () => {
    if (leaseCount !== 0 || !ownedByProbe || closeAttempt) return;
    const token = `close-${lifecycleSequence += 1}`;
    const completion = Promise.resolve().then(
      () => environment.chrome.offscreen.closeDocument(),
    );
    closeAttempt = token;
    completion.then(
      () => {
        void serialize(async () => {
          if (closeAttempt !== token) return;
          closeAttempt = null;
          ownedByProbe = false;
        });
      },
      () => {
        void serialize(async () => {
          if (closeAttempt !== token) return;
          closeAttempt = null;
          try {
            const exists = await withDeadline(
              () => environment.chrome.offscreen.hasDocument(),
              lifecycleTimeoutMs,
            );
            if (!exists) ownedByProbe = false;
          } catch {
            // Preserve ownership until a later acquire can re-check the document.
          }
        });
      },
    );
    try {
      await withDeadline(() => completion, lifecycleTimeoutMs);
    } catch {
      // Detached completion reconciles ownership and keeps this queue bounded.
    }
  };

  const beginCreate = async (deadlineAt) => {
    const completion = Promise.resolve().then(
      () => environment.chrome.offscreen.createDocument({
        justification: 'SIX API capability probe context',
        reasons: ['DOM_PARSER'],
        url: 'offscreen-probe.html',
      }),
    );
    try {
      await withDeadline(() => completion, boundedTimeout(deadlineAt));
      ownedByProbe = true;
    } catch (error) {
      let reconciled = false;
      completion.then(
        () => {
          if (reconciled) return;
          reconciled = true;
          void serialize(async () => {
            ownedByProbe = true;
            if (leaseCount === 0) await beginClose();
          });
        },
        () => { reconciled = true; },
      );
      throw error;
    }
  };

  return {
    acquire(deadlineAt) {
      return serialize(async () => {
        if (Date.now() >= deadlineAt) throw new ProbeTimeoutError(0);
        if (leaseCount === 0) {
          const existed = await withDeadline(
            () => environment.chrome.offscreen.hasDocument(),
            boundedTimeout(deadlineAt),
          );
          if (!existed) {
            if (closeAttempt) throw new Error('offscreen close is still pending');
            if (Date.now() >= deadlineAt) throw new ProbeTimeoutError(0);
            await beginCreate(deadlineAt);
          } else if (!closeAttempt && !ownedByProbe) {
            ownedByProbe = false;
          }
        }
        leaseCount += 1;
        let released = false;
        return () => {
          if (released) return Promise.resolve();
          released = true;
          return serialize(async () => {
            leaseCount = Math.max(0, leaseCount - 1);
            await beginClose();
          });
        };
      });
    },
  };
}

async function dispatchOffscreenCase(
  environment,
  testCase,
  groupId,
  runToken,
  record,
  timeoutMs,
  leases,
) {
  const deadlineAt = Date.now() + timeoutMs;
  const acquired = leases.acquire(deadlineAt);
  let release;
  record.expectedSender = {
    id: environment.chrome.runtime.id,
    origin: new URL(environment.chrome.runtime.getURL('/')).origin,
    tabId: null,
    url: environment.chrome.runtime.getURL('offscreen-probe.html'),
  };
  try {
    return await withDeadline(async () => {
      release = await acquired;
      const dispatched = await callbackResult(
        environment,
        (done) => environment.chrome.runtime.sendMessage(
          {
            bridgeTarget: 'offscreen',
            caseId: testCase.id,
            descriptor: testCase,
            groupId,
            runToken,
            type: 'six-probe-run',
          },
          done,
        ),
        timeoutMs,
      );
      if (
        dispatched?.type !== 'six-probe-dispatched'
        || dispatched.caseId !== testCase.id
        || dispatched.runToken !== runToken
      ) {
        throw new Error('offscreen probe dispatch response mismatch');
      }
      return record.resultPromise;
    }, timeoutMs);
  } finally {
    if (!release) {
      try {
        release = await withDeadline(() => acquired, CLEANUP_TIMEOUT_MS);
      } catch {
        void acquired.then(
          (lateRelease) => lateRelease().catch(() => {}),
          () => {},
        );
      }
    }
    if (release) {
      try {
        await withDeadline(() => release(), CLEANUP_TIMEOUT_MS);
      } catch {
        // Release remains serialized and continues after this bounded wait.
      }
    }
  }
}

async function loadController(environment, fetchImpl, options) {
  const response = await fetchImpl(environment.chrome.runtime.getURL('probe-cases.json'));
  if (!response.ok) throw new Error(`probe descriptor load failed: ${response.status}`);
  const bundle = await response.json();
  const factory = CASE_FACTORIES[bundle.groupId];
  if (!factory) throw new Error('unknown probe group');
  const descriptors = new Map(bundle.cases.map((testCase) => [testCase.id, testCase]));
  const offscreenLeases = createOffscreenLeaseManager(
    environment,
    options.lifecycleTimeoutMs,
  );
  return createProbeController({
    descriptors,
    executeContextCase: (testCase, runToken, record, timeoutMs) => (
      testCase.context === 'content_script'
        ? dispatchContentCase(environment, testCase, bundle.groupId, runToken, record, timeoutMs)
        : dispatchOffscreenCase(
          environment,
          testCase,
          bundle.groupId,
          runToken,
          record,
          timeoutMs,
          offscreenLeases,
        )
    ),
    executeWorkerCase: (testCase, runToken, timeoutMs) => runCase(
      factory(testCase, environment),
      runToken,
      timeoutMs,
    ),
    extensionPageUrl: environment.chrome.runtime.getURL('probe.html'),
    runtimeId: environment.chrome.runtime.id,
    timeoutMs: options.timeoutMs,
    tokenFactory: options.tokenFactory,
  });
}

export function installServiceWorkerBridge(
  environment = globalThis,
  fetchImpl = globalThis.fetch,
  options = {},
) {
  let controllerPromise;
  const listener = (message, sender, sendResponse) => {
    if (
      message?.bridgeTarget
      || (message?.type !== 'six-probe-run' && message?.type !== 'six-probe-result')
    ) return false;
    controllerPromise ??= loadController(environment, fetchImpl, options);
    controllerPromise.then(
      (controller) => controller.handleMessage(message, sender),
    ).then(
      (response) => sendResponse(response),
      (error) => sendResponse(resultEnvelope(failedResult(
        {
          context: 'unknown',
          id: String(message?.caseId ?? 'unknown'),
          member: 'unknown',
          namespace: 'unknown',
        },
        options.tokenFactory?.() ?? randomRunToken(environment),
        error,
      ))),
    ).catch(() => {
      // sendResponse itself failed because the caller disappeared.
    });
    return true;
  };
  environment.chrome.runtime.onMessage.addListener(listener);
  return listener;
}

if (globalThis.chrome?.runtime?.onMessage && globalThis.fetch) {
  installServiceWorkerBridge(globalThis, globalThis.fetch);
}
