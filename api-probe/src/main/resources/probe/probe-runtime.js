function classifyError(error) {
  const message = String(error?.message ?? error);
  return /permission|denied|not allowed|policy/i.test(message) ? 'RESTRICTED' : 'FAILED';
}

function serializeError(error) {
  return {
    kind: 'error',
    message: String(error?.message ?? error).replace(/\s+/g, ' ').trim().slice(0, 512),
    name: String(error?.name ?? 'Error').slice(0, 80),
  };
}

function successDetail(value) {
  return {
    kind: 'success',
    valueType: value === null ? 'null' : typeof value,
  };
}

function normalize(testCase, status, detail, startedAt) {
  return {
    caseId: String(testCase.id),
    context: String(testCase.context),
    detail,
    durationMs: Math.max(0, Date.now() - startedAt),
    member: String(testCase.member),
    namespace: String(testCase.namespace),
    status,
  };
}

export async function runCase(testCase) {
  const startedAt = Date.now();
  try {
    if (typeof testCase.resolve !== 'function') {
      throw new TypeError('probe case resolver is required');
    }
    const member = testCase.resolve();
    if (member === undefined || member === null) {
      return normalize(testCase, 'ABSENT', { kind: 'absent' }, startedAt);
    }
    if (testCase.mode === 'presence_only') {
      return normalize(testCase, 'EXPOSED', { kind: 'exposed' }, startedAt);
    }
    if (testCase.mode !== 'safe_call' && testCase.mode !== 'safe_call_with_cleanup') {
      throw new TypeError('unsupported probe mode');
    }
    if (typeof testCase.run !== 'function') {
      throw new TypeError('safe probe runner is required');
    }
    const result = await testCase.run();
    return normalize(testCase, 'VERIFIED', successDetail(result), startedAt);
  } catch (error) {
    return normalize(testCase, classifyError(error), serializeError(error), startedAt);
  }
}

export function resolveMember(environment, namespace, member) {
  return environment?.chrome?.[namespace]?.[member];
}

export function callbackResult(environment, invoke) {
  return new Promise((resolve, reject) => {
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
      if (returned && typeof returned.then === 'function') {
        returned.then(complete, reject);
      } else if (returned !== undefined) {
        complete(returned);
      }
    } catch (error) {
      reject(error);
    }
  });
}

export function callbackCompletion(environment, invoke) {
  return new Promise((resolve, reject) => {
    try {
      invoke((value) => {
        const lastError = environment?.chrome?.runtime?.lastError;
        if (lastError) reject(new Error(String(lastError.message ?? lastError)));
        else resolve(value);
      });
    } catch (error) {
      reject(error);
    }
  });
}

export function createRuntimeCase(descriptor, environment, caseIds, safeRunners) {
  if (!caseIds.includes(descriptor?.id)) {
    throw new TypeError('unknown runtime probe case');
  }
  const runner = safeRunners[descriptor.id];
  return {
    ...descriptor,
    resolve: () => resolveMember(environment, descriptor.namespace, descriptor.member),
    run: runner ? () => runner(environment, descriptor.cleanup) : undefined,
  };
}
