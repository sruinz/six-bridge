function classifyError(error) {
  const message = String(error?.message ?? error);
  return /permission|denied|not allowed|policy/i.test(message) ? 'RESTRICTED' : 'FAILED';
}

function serializeError(error) {
  return {
    name: String(error?.name ?? 'Error'),
    message: String(error?.message ?? error),
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
      return normalize(testCase, 'ABSENT', null, startedAt);
    }
    if (testCase.mode === 'presence_only') {
      return normalize(testCase, 'EXPOSED', null, startedAt);
    }
    if (testCase.mode !== 'safe_call' && testCase.mode !== 'safe_call_with_cleanup') {
      throw new TypeError('unsupported probe mode');
    }
    if (typeof testCase.run !== 'function') {
      throw new TypeError('safe probe runner is required');
    }
    const result = await testCase.run();
    return normalize(testCase, 'VERIFIED', result, startedAt);
  } catch (error) {
    return normalize(testCase, classifyError(error), serializeError(error), startedAt);
  }
}
