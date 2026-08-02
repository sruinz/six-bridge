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
    const result = await testCase.run();
    return normalize(testCase, 'VERIFIED', result, startedAt);
  } catch (error) {
    return normalize(testCase, classifyError(error), serializeError(error), startedAt);
  }
}
