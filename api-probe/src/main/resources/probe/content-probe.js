(() => {
  const FIXTURE_ORIGIN = 'https://example.com/';
  const MARKER = '#sixbridge-api-probe=';
  const GROUP_IDS = new Set([
    'advanced-runtime',
    'base-runtime',
    'identity-privacy',
    'network',
    'tab-page',
    'user-interface',
  ]);

  if (!location.href.startsWith(FIXTURE_ORIGIN) || !location.hash.startsWith(MARKER)) return;

  globalThis.chrome?.runtime?.onMessage?.addListener((message, _sender, sendResponse) => {
    if (
      message?.type !== 'six-probe-run'
      || message.descriptor?.context !== 'content_script'
      || !message.caseId
      || !GROUP_IDS.has(message.groupId)
    ) {
      return false;
    }
    Promise.all([
      import(globalThis.chrome.runtime.getURL('probe-runtime.js')),
      import(globalThis.chrome.runtime.getURL(`cases/${String(message.groupId)}.js`)),
    ]).then(([runtime, group]) => {
      const descriptor = message.descriptor;
      if (descriptor?.id !== message.caseId || descriptor?.context !== 'content_script') {
        throw new Error('content probe descriptor mismatch');
      }
      return runtime.runCase(group.createCase(descriptor, globalThis));
    }).then((result) => {
      sendResponse({ result, type: 'six-probe-result' });
    }, (error) => {
      sendResponse({
        result: { caseId: String(message.caseId), status: 'FAILED' },
        type: 'six-probe-result',
        error: String(error?.message ?? error),
      });
    });
    return true;
  });
})();
