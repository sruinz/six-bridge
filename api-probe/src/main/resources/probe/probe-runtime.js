const ALLOWED_STATUSES = new Set([
  'ABSENT',
  'EXPOSED',
  'FAILED',
  'NOT_RUN',
  'RESTRICTED',
  'VERIFIED',
]);
const RESULT_FIELDS = [
  'caseId',
  'context',
  'detail',
  'durationMs',
  'member',
  'namespace',
  'runToken',
  'status',
];
const DEFAULT_TIMEOUT_MS = 10_000;
const LOCAL_RUN_TOKEN = 'local-run-00000000';
const RUN_TOKEN = /^[A-Za-z0-9_-]{16,64}$/;

export class ProbeTimeoutError extends Error {
  constructor(timeoutMs) {
    super(`probe timeout after ${timeoutMs}ms`);
    this.name = 'ProbeTimeoutError';
  }
}

function normalizeText(value, maxLength) {
  return String(value).replace(/\s+/g, ' ').trim().slice(0, maxLength);
}

function classifyError(error) {
  const message = String(error?.message ?? error);
  return /permission|denied|not allowed|policy/i.test(message) ? 'RESTRICTED' : 'FAILED';
}

function serializeError(error) {
  return {
    kind: 'error',
    message: normalizeText(error?.message ?? error, 512),
    name: normalizeText(error?.name ?? 'Error', 80),
  };
}

function successDetail(value) {
  return {
    kind: 'success',
    valueType: value === null ? 'null' : typeof value,
  };
}

function result(testCase, runToken, status, detail, startedAt, durationMs) {
  return {
    caseId: String(testCase.id),
    context: String(testCase.context),
    detail,
    durationMs: durationMs ?? Math.max(0, Date.now() - startedAt),
    member: String(testCase.member),
    namespace: String(testCase.namespace),
    runToken: String(runToken),
    status,
  };
}

export function failedResult(testCase, runToken, error, durationMs = 0) {
  return result(testCase, runToken, 'FAILED', serializeError(error), Date.now(), durationMs);
}

export function notRunResult(testCase, runToken) {
  return result(
    testCase,
    runToken,
    'NOT_RUN',
    { kind: 'gesture-required' },
    Date.now(),
    0,
  );
}

function assertPlainBoundedDetail(detail) {
  if (
    !detail
    || typeof detail !== 'object'
    || Array.isArray(detail)
    || (Object.getPrototypeOf(detail) !== Object.prototype && Object.getPrototypeOf(detail) !== null)
  ) {
    throw new TypeError('probe result detail must be an object');
  }
  const encoded = JSON.stringify(detail);
  if (!encoded || encoded.length > 2_048) throw new TypeError('probe result detail is too large');
  const stack = [{ depth: 0, value: detail }];
  while (stack.length > 0) {
    const { depth, value } = stack.pop();
    if (depth > 6) throw new TypeError('probe result detail is too deep');
    if (value && typeof value === 'object') {
      if (
        !Array.isArray(value)
        && Object.getPrototypeOf(value) !== Object.prototype
        && Object.getPrototypeOf(value) !== null
      ) throw new TypeError('probe result detail must contain plain values');
      const values = Array.isArray(value) ? value : Object.values(value);
      if (values.length > 64) throw new TypeError('probe result detail has too many values');
      values.forEach((child) => stack.push({ depth: depth + 1, value: child }));
    } else if (
      value !== null
      && typeof value !== 'string'
      && typeof value !== 'number'
      && typeof value !== 'boolean'
    ) {
      throw new TypeError('probe result detail contains an unsupported value');
    } else if (typeof value === 'number' && !Number.isFinite(value)) {
      throw new TypeError('probe result detail contains a non-finite number');
    } else if (typeof value === 'string' && value.length > 1_024) {
      throw new TypeError('probe result detail string is too large');
    }
  }
}

export function validateResultEnvelope(envelope, testCase, runToken) {
  if (
    !envelope
    || Object.keys(envelope).sort().join('\0') !== 'result\0type'
    || envelope.type !== 'six-probe-result'
    || !envelope.result
  ) {
    throw new TypeError('invalid probe result envelope');
  }
  const value = envelope.result;
  if (Object.keys(value).sort().join('\0') !== RESULT_FIELDS.slice().sort().join('\0')) {
    throw new TypeError('invalid probe result fields');
  }
  if (!RUN_TOKEN.test(String(runToken)) || value.runToken !== runToken) {
    throw new TypeError('probe result run token mismatch');
  }
  if (
    value.caseId !== testCase.id
    || value.namespace !== testCase.namespace
    || value.member !== testCase.member
    || value.context !== testCase.context
  ) {
    throw new TypeError('probe result descriptor mismatch');
  }
  if (!ALLOWED_STATUSES.has(value.status)) throw new TypeError('invalid probe result status');
  if (!Number.isInteger(value.durationMs) || value.durationMs < 0 || value.durationMs > 600_000) {
    throw new TypeError('invalid probe result duration');
  }
  assertPlainBoundedDetail(value.detail);
  return {
    type: 'six-probe-result',
    result: JSON.parse(JSON.stringify(value)),
  };
}

export function withDeadline(operation, timeoutMs = DEFAULT_TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      reject(new ProbeTimeoutError(timeoutMs));
    }, timeoutMs);
    const complete = (callback, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      callback(value);
    };
    try {
      Promise.resolve(operation()).then(
        (value) => complete(resolve, value),
        (error) => complete(reject, error),
      );
    } catch (error) {
      complete(reject, error);
    }
  });
}

export async function runCase(testCase, runToken = LOCAL_RUN_TOKEN, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const startedAt = Date.now();
  try {
    if (!RUN_TOKEN.test(runToken)) throw new TypeError('invalid probe run token');
    if (typeof testCase.resolve !== 'function') throw new TypeError('probe case resolver is required');
    const member = testCase.resolve();
    if (member === undefined || member === null) {
      return result(testCase, runToken, 'ABSENT', { kind: 'absent' }, startedAt);
    }
    if (testCase.mode === 'presence_only') {
      return result(testCase, runToken, 'EXPOSED', { kind: 'exposed' }, startedAt);
    }
    if (testCase.mode !== 'safe_call' && testCase.mode !== 'safe_call_with_cleanup') {
      throw new TypeError('unsupported probe mode');
    }
    if (typeof testCase.run !== 'function') throw new TypeError('safe probe runner is required');
    const value = testCase.mode === 'safe_call_with_cleanup'
      ? await testCase.run(runToken, timeoutMs)
      : await withDeadline(() => testCase.run(runToken, timeoutMs), timeoutMs);
    return result(testCase, runToken, 'VERIFIED', successDetail(value), startedAt);
  } catch (error) {
    return result(testCase, runToken, classifyError(error), serializeError(error), startedAt);
  }
}

export function resolveMember(environment, namespace, member) {
  return environment?.chrome?.[namespace]?.[member];
}

export function callbackResult(environment, invoke, timeoutMs = DEFAULT_TIMEOUT_MS) {
  return withDeadline(() => new Promise((resolve, reject) => {
    let settled = false;
    const complete = (value) => {
      if (settled) return;
      settled = true;
      const lastError = environment?.chrome?.runtime?.lastError;
      if (lastError) reject(new Error(String(lastError.message ?? lastError)));
      else resolve(value);
    };
    try {
      const returned = invoke(complete);
      if (returned && typeof returned.then === 'function') returned.then(complete, reject);
      else if (returned !== undefined) complete(returned);
    } catch (error) {
      reject(error);
    }
  }), timeoutMs);
}

export function callbackCompletion(environment, invoke, timeoutMs = DEFAULT_TIMEOUT_MS) {
  return withDeadline(() => new Promise((resolve, reject) => {
    try {
      invoke((value) => {
        const lastError = environment?.chrome?.runtime?.lastError;
        if (lastError) reject(new Error(String(lastError.message ?? lastError)));
        else resolve(value);
      });
    } catch (error) {
      reject(error);
    }
  }), timeoutMs);
}

export function createRuntimeCase(descriptor, environment, caseIds, safeRunners) {
  if (!caseIds.includes(descriptor?.id)) throw new TypeError('unknown runtime probe case');
  const runner = safeRunners[descriptor.id];
  return {
    ...descriptor,
    resolve: () => resolveMember(environment, descriptor.namespace, descriptor.member),
    run: runner ? (runToken, timeoutMs) => runner(
      environment,
      descriptor.cleanup,
      runToken,
      timeoutMs,
    ) : undefined,
  };
}
