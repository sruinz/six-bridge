(() => {
  const GROUP_IDS = new Set([
    'advanced-runtime',
    'base-runtime',
    'identity-privacy',
    'network',
    'tab-page',
    'user-interface',
  ]);
  const FIXTURE_URL = 'https://example.com/';
  function currentUrl() {
    return new URL(globalThis.location.href);
  }

  const url = currentUrl();
  const marker = new URLSearchParams(url.hash.slice(1));
  const markerCaseId = marker.get('sixbridge-api-probe');
  const markerRunToken = marker.get('runToken');

  if (url.origin !== new URL(FIXTURE_URL).origin || !markerCaseId || !markerRunToken) return;

  async function sendResult(runtime, descriptor, runToken, result) {
    const ack = await runtime.callbackResult(
      globalThis,
      (done) => globalThis.chrome.runtime.sendMessage(
        { result, type: 'six-probe-result' },
        done,
      ),
      1_000,
    );
    if (
      ack?.type !== 'six-probe-ack'
      || ack.caseId !== descriptor.id
      || ack.runToken !== runToken
    ) {
      throw new Error('content probe acknowledgement mismatch');
    }
  }

  async function execute(message) {
    let runtime;
    const descriptor = message.descriptor;
    try {
      runtime = await import(globalThis.chrome.runtime.getURL('probe-runtime.js'));
      const group = await import(
        globalThis.chrome.runtime.getURL('cases/__SIXBRIDGE_PROBE_GROUP__.js')
      );
      if (
        descriptor?.id !== message.caseId
        || descriptor?.context !== 'content_script'
      ) {
        throw new Error('content probe descriptor mismatch');
      }
      const result = await runtime.runCase(
        group.createCase(descriptor, globalThis),
        message.runToken,
        8_000,
      );
      await sendResult(runtime, descriptor, message.runToken, result);
    } catch (error) {
      if (!runtime || !descriptor?.id) return;
      const result = runtime.failedResult(descriptor, message.runToken, error);
      try {
        await sendResult(runtime, descriptor, message.runToken, result);
      } catch {
        // The service worker already expired or rejected this execution.
      }
    }
  }

  globalThis.chrome?.runtime?.onMessage?.addListener((message, _sender, sendResponse) => {
    if (
      message?.type !== 'six-probe-run'
      || message.descriptor?.context !== 'content_script'
      || message.caseId !== markerCaseId
      || message.runToken !== markerRunToken
      || !GROUP_IDS.has(message.groupId)
    ) {
      return false;
    }
    sendResponse({
      caseId: message.caseId,
      runToken: message.runToken,
      type: 'six-probe-dispatched',
    });
    void execute(message);
    return false;
  });
})();
