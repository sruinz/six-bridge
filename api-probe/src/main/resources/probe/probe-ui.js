const byId = (id) => document.getElementById(id);
const GROUP_IDS = new Set([
  'advanced-runtime',
  'base-runtime',
  'identity-privacy',
  'network',
  'tab-page',
  'user-interface',
]);

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

export function setSummary(counts) {
  const buckets = summaryBuckets(counts);
  byId('count-ok').textContent = String(buckets.ok);
  byId('count-restricted').textContent = String(buckets.restricted);
  byId('count-absent').textContent = String(buckets.absent);
  byId('count-failed').textContent = String(buckets.failed);
  byId('count-not-run').textContent = String(buckets.notRun);
}

export function appendResult(result) {
  const item = document.createElement('li');
  const label = document.createElement('strong');
  label.textContent = `${String(result.namespace)}.${String(result.member)}`;
  const detail = document.createElement('span');
  detail.textContent = ` · ${String(result.status)} · ${String(result.context)}`;
  item.append(label, detail);
  byId('case-results').append(item);
}

export function setGesture(description, handler) {
  const region = byId('gesture');
  byId('gesture-description').textContent = String(description);
  const button = byId('gesture-run');
  button.onclick = handler;
  region.hidden = false;
}

export function setEvidenceStatus(status) {
  byId('evidence-status').textContent = `증거 전송: ${String(status)}`;
}

function timeoutResult(testCase) {
  return {
    caseId: String(testCase.id),
    context: String(testCase.context),
    detail: { kind: 'error', message: 'probe timeout after 10000ms', name: 'ProbeError' },
    durationMs: 10_000,
    member: String(testCase.member),
    namespace: String(testCase.namespace),
    status: 'FAILED',
  };
}

function sendMessage(message) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const done = (value) => {
      if (settled) return;
      settled = true;
      const lastError = globalThis.chrome?.runtime?.lastError;
      if (lastError) reject(new Error(String(lastError.message ?? lastError)));
      else resolve(value);
    };
    try {
      const returned = globalThis.chrome.runtime.sendMessage(message, done);
      if (returned && typeof returned.then === 'function') returned.then(done, reject);
    } catch (error) {
      reject(error);
    }
  });
}

async function timed(testCase, operation) {
  let timer;
  try {
    return await Promise.race([
      operation(),
      new Promise((resolve) => {
        timer = setTimeout(() => resolve(timeoutResult(testCase)), 10_000);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

export async function startProbe() {
  const descriptorResponse = await fetch('probe-cases.json');
  if (!descriptorResponse.ok) throw new Error(`probe descriptor load failed: ${descriptorResponse.status}`);
  const bundle = await descriptorResponse.json();
  if (!GROUP_IDS.has(bundle.groupId)) throw new Error('unknown probe group');
  const [{ runCase }, group] = await Promise.all([
    import('./probe-runtime.js'),
    import(`./cases/${String(bundle.groupId)}.js`),
  ]);
  const counts = { ABSENT: 0, EXPOSED: 0, FAILED: 0, NOT_RUN: 0, RESTRICTED: 0, VERIFIED: 0 };
  setSummary(counts);
  setProgress(0, bundle.cases.length, '미실행');

  for (const [index, testCase] of bundle.cases.entries()) {
    setProgress(index + 1, bundle.cases.length, testCase.context);
    let result;
    if (testCase.context === 'user_gesture') {
      result = { ...timeoutResult(testCase), detail: { kind: 'gesture-required' }, durationMs: 0, status: 'NOT_RUN' };
      setGesture(`${testCase.namespace}.${testCase.member}`, async () => {
        const executed = await timed(testCase, () => runCase(group.createCase(testCase, globalThis)));
        await sendMessage({ result: executed, type: 'six-probe-result' });
        appendResult(executed);
      });
    } else {
      result = await timed(testCase, async () => {
        const response = await sendMessage({ caseId: testCase.id, type: 'six-probe-run' });
        if (response?.type !== 'six-probe-dispatch') return response;
        if (response.context !== 'extension_page') {
          throw new Error(`unsupported probe context dispatch: ${response.context}`);
        }
        const executed = await runCase(group.createCase(testCase, globalThis));
        await sendMessage({ result: executed, type: 'six-probe-result' });
        return executed;
      });
    }
    counts[result?.status in counts ? result.status : 'FAILED'] += 1;
    appendResult(result ?? timeoutResult(testCase));
    setSummary(counts);
  }
  setEvidenceStatus('준비');
}

if (globalThis.document && globalThis.chrome?.runtime) {
  startProbe().catch((error) => setEvidenceStatus(`실패 · ${String(error?.message ?? error)}`));
}
