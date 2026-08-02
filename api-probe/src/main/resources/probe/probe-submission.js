const ENDPOINT = 'http://127.0.0.1:47171/evidence';
const GROUP_IDS = new Set([
  'advanced-runtime', 'base-runtime', 'identity-privacy',
  'network', 'tab-page', 'user-interface',
]);
const SHA256 = /^[0-9a-f]{64}$/;
const TERMINAL = new Set(['ABSENT', 'EXPOSED', 'FAILED', 'RESTRICTED', 'VERIFIED']);
const RESULT_FIELDS = [
  'caseId', 'context', 'detail', 'durationMs', 'member', 'namespace', 'runToken', 'status',
];

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
}

function validateResult(value, caseIds) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('invalid result');
  if (Object.keys(value).sort().join('\0') !== RESULT_FIELDS.slice().sort().join('\0')) {
    throw new TypeError('invalid result fields');
  }
  if (!caseIds.has(value.caseId)) throw new TypeError('unknown result caseId');
  if (value.status !== 'NOT_RUN' && !TERMINAL.has(value.status)) throw new TypeError('invalid result status');
  return JSON.parse(JSON.stringify(value));
}

export function createSubmissionController({
  catalogSha256,
  caseIds,
  fetchImpl = globalThis.fetch?.bind(globalThis),
  groupId,
  onStatus = () => {},
}) {
  if (!GROUP_IDS.has(groupId) || !SHA256.test(String(catalogSha256))) {
    throw new TypeError('invalid submission provenance');
  }
  if (!Array.isArray(caseIds) || caseIds.length < 1 || new Set(caseIds).size !== caseIds.length) {
    throw new TypeError('invalid submission caseIds');
  }
  if (typeof fetchImpl !== 'function' || typeof onStatus !== 'function') {
    throw new TypeError('invalid submission dependencies');
  }
  const expected = new Set(caseIds);
  const results = new Map();
  let frozen = null;
  let submissionState = 'PENDING';

  async function postFrozen() {
    if (!frozen || submissionState === 'STORED' || submissionState === 'CONFLICT') return submissionState;
    submissionState = 'SENDING';
    onStatus('전송 중', submissionState);
    try {
      const response = await fetchImpl(ENDPOINT, {
        body: frozen,
        credentials: 'omit',
        headers: { 'Content-Type': 'application/json' },
        method: 'POST',
        redirect: 'error',
      });
      if (response.status === 200 || response.status === 201) {
        submissionState = 'STORED';
        onStatus('완료', submissionState);
      } else if (response.status === 409) {
        submissionState = 'CONFLICT';
        onStatus('충돌 · 새 session 필요', submissionState);
      } else {
        submissionState = 'RETRYABLE';
        onStatus(`실패 · HTTP ${String(response.status)}`, submissionState);
      }
    } catch (error) {
      submissionState = 'RETRYABLE';
      onStatus(`실패 · ${String(error?.message ?? error)}`, submissionState);
    }
    return submissionState;
  }

  async function recordResult(value) {
    if (frozen) return submissionState;
    const copy = validateResult(value, expected);
    results.set(copy.caseId, copy);
    if (results.size === expected.size
      && [...results.values()].every((result) => TERMINAL.has(result.status))) {
      frozen = `${JSON.stringify(canonical({
        catalogSha256,
        groupId,
        results: [...results.values()].sort((left, right) => left.caseId.localeCompare(right.caseId)),
        schemaVersion: 1,
      }))}\n`;
      await postFrozen();
    }
    return submissionState;
  }

  return Object.freeze({ recordResult, retry: postFrozen, state: () => submissionState });
}
