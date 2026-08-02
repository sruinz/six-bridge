const byId = (id) => document.getElementById(id);
const GROUP_IDS = new Set([
  'advanced-runtime',
  'base-runtime',
  'identity-privacy',
  'network',
  'tab-page',
  'user-interface',
]);
const CASE_TIMEOUT_MS = 8_000;

export function setProgress(current, total, context) {
  byId('progress-current').textContent = String(current);
  byId('progress-total').textContent = String(total);
  byId('current-context').textContent = `실행 context: ${String(context)}`;
}

export function summaryBuckets(counts) {
  return {
    ok: Number(counts?.VERIFIED ?? 0) + Number(counts?.EXPOSED ?? 0),
    restricted: Number(counts?.RESTRICTED ?? 0),
    absent: Number(counts?.ABSENT ?? 0),
    failed: Number(counts?.FAILED ?? 0),
    notRun: Number(counts?.NOT_RUN ?? 0),
  };
}

export function transitionCounts(counts, previousStatus, nextStatus) {
  if (previousStatus && previousStatus in counts) counts[previousStatus] -= 1;
  if (nextStatus in counts) counts[nextStatus] += 1;
  return counts;
}

export function setSummary(counts) {
  const buckets = summaryBuckets(counts);
  byId('count-ok').textContent = String(buckets.ok);
  byId('count-restricted').textContent = String(buckets.restricted);
  byId('count-absent').textContent = String(buckets.absent);
  byId('count-failed').textContent = String(buckets.failed);
  byId('count-not-run').textContent = String(buckets.notRun);
}

function renderResult(item, result) {
  item.replaceChildren();
  const label = document.createElement('strong');
  label.textContent = `${String(result.namespace)}.${String(result.member)}`;
  const detail = document.createElement('span');
  detail.textContent = ` · ${String(result.status)} · ${String(result.context)}`;
  item.append(label, detail);
}

export function appendResult(result) {
  const item = document.createElement('li');
  renderResult(item, result);
  byId('case-results').append(item);
  return item;
}

export function replaceResult(item, result) {
  renderResult(item, result);
}

export function createGestureRunner({ button, execute, onResult }) {
  let started = false;
  return async () => {
    if (started) return null;
    started = true;
    button.disabled = true;
    const result = await execute();
    onResult(result);
    button.hidden = true;
    return result;
  };
}

export function setGesture(description, handler) {
  const region = byId('gesture');
  byId('gesture-description').textContent = String(description);
  const button = byId('gesture-run');
  button.disabled = false;
  button.hidden = false;
  button.onclick = handler;
  region.hidden = false;
  return button;
}

export function setEvidenceStatus(status) {
  byId('evidence-status').textContent = `증거 전송: ${String(status)}`;
}

function uiFailureToken() {
  return `ui-failure-${Date.now().toString(36).padStart(16, '0')}`.slice(0, 64);
}

async function sendMessage(runtime, message, timeoutMs = 10_000) {
  return runtime.callbackResult(
    globalThis,
    (done) => globalThis.chrome.runtime.sendMessage(message, done),
    timeoutMs,
  );
}

function validateDispatch(response, testCase) {
  if (
    response?.type !== 'six-probe-dispatch'
    || response.caseId !== testCase.id
    || response.context !== testCase.context
    || typeof response.runToken !== 'string'
  ) {
    throw new Error('probe dispatch response mismatch');
  }
  return response;
}

async function sendResult(runtime, testCase, runToken, result) {
  const ack = await sendMessage(runtime, { result, type: 'six-probe-result' }, 1_000);
  if (
    ack?.type !== 'six-probe-ack'
    || ack.caseId !== testCase.id
    || ack.runToken !== runToken
  ) {
    throw new Error('probe result acknowledgement mismatch');
  }
}

async function executeDispatchedPageCase(runtime, group, testCase, dispatch) {
  const result = await runtime.runCase(
    group.createCase(testCase, globalThis),
    dispatch.runToken,
    CASE_TIMEOUT_MS,
  );
  await sendResult(runtime, testCase, dispatch.runToken, result);
  return result;
}

async function requestCase(runtime, group, testCase) {
  try {
    const response = await sendMessage(
      runtime,
      { caseId: testCase.id, type: 'six-probe-run' },
      10_000,
    );
    if (response?.type === 'six-probe-result') {
      return runtime.validateResultEnvelope(
        response,
        testCase,
        response.result?.runToken,
      ).result;
    }
    const dispatch = validateDispatch(response, testCase);
    if (dispatch.context !== 'extension_page') {
      throw new Error(`unsupported probe context dispatch: ${dispatch.context}`);
    }
    return executeDispatchedPageCase(runtime, group, testCase, dispatch);
  } catch (error) {
    return runtime.failedResult(testCase, uiFailureToken(), error);
  }
}

export async function startProbe() {
  const descriptorResponse = await fetch('probe-cases.json');
  if (!descriptorResponse.ok) throw new Error(`probe descriptor load failed: ${descriptorResponse.status}`);
  const bundle = await descriptorResponse.json();
  if (!GROUP_IDS.has(bundle.groupId)) throw new Error('unknown probe group');
  const [runtime, group] = await Promise.all([
    import('./probe-runtime.js'),
    import(`./cases/${String(bundle.groupId)}.js`),
  ]);
  const counts = { ABSENT: 0, EXPOSED: 0, FAILED: 0, NOT_RUN: 0, RESTRICTED: 0, VERIFIED: 0 };
  setSummary(counts);
  setProgress(0, bundle.cases.length, '미실행');

  for (const [index, testCase] of bundle.cases.entries()) {
    setProgress(index + 1, bundle.cases.length, testCase.context);
    if (testCase.context === 'user_gesture') {
      const waiting = runtime.notRunResult(testCase, uiFailureToken());
      const item = appendResult(waiting);
      transitionCounts(counts, null, 'NOT_RUN');
      const button = setGesture(`${testCase.namespace}.${testCase.member}`, null);
      button.onclick = createGestureRunner({
        button,
        execute: async () => {
          let dispatch;
          try {
            dispatch = validateDispatch(
              await sendMessage(runtime, { caseId: testCase.id, type: 'six-probe-run' }),
              testCase,
            );
            runtime.validateResultEnvelope(
              { result: dispatch.initialResult, type: 'six-probe-result' },
              testCase,
              dispatch.runToken,
            );
            return await executeDispatchedPageCase(runtime, group, testCase, dispatch);
          } catch (error) {
            return runtime.failedResult(testCase, dispatch?.runToken ?? uiFailureToken(), error);
          }
        },
        onResult: (result) => {
          replaceResult(item, result);
          transitionCounts(counts, 'NOT_RUN', result.status);
          setSummary(counts);
        },
      });
      setSummary(counts);
      continue;
    }

    const result = await requestCase(runtime, group, testCase);
    transitionCounts(counts, null, result.status);
    appendResult(result);
    setSummary(counts);
  }
  setEvidenceStatus('준비');
}

if (globalThis.document && globalThis.chrome?.runtime) {
  startProbe().catch((error) => setEvidenceStatus(`실패 · ${String(error?.message ?? error)}`));
}
