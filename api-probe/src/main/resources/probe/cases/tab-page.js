import { callbackResult, createRuntimeCase } from '../probe-runtime.js';

const FIXTURE_URL = 'https://example.com/#sixbridge-api-probe=';
const CLEANUP_TIMEOUT_MS = 1_000;

export const CASE_IDS = Object.freeze([
  'search-query-page',
  'tab-groups-query-page',
  'tabs-create-fixture-page',
  'windows-get-current-page',
]);

const SAFE_RUNNERS = Object.freeze({
  'tabs-create-fixture-page': async (environment, cleanup, runToken, timeoutMs) => {
    if (cleanup?.member !== 'remove' || cleanup?.target !== 'created_resource_id') {
      throw new Error('tabs cleanup contract mismatch');
    }
    const fixtureUrl = `${FIXTURE_URL}tabs-create-fixture-page&runToken=${encodeURIComponent(runToken)}`;
    let createdTabId;
    try {
      const tab = await callbackResult(
        environment,
        (done) => environment.chrome.tabs.create({ active: false, url: fixtureUrl }, done),
        timeoutMs,
      );
      createdTabId = tab?.id;
      if (!Number.isInteger(createdTabId)) throw new Error('created tab ID is missing');
      return { created: true };
    } finally {
      const cleanupDeadline = Date.now() + CLEANUP_TIMEOUT_MS;
      const cleanupRemaining = () => Math.max(1, cleanupDeadline - Date.now());
      try {
        let cleanupIds = Number.isInteger(createdTabId) ? [createdTabId] : [];
        if (cleanupIds.length === 0) {
          const tabs = await callbackResult(
            environment,
            (done) => environment.chrome.tabs.query({ url: 'https://example.com/*' }, done),
            cleanupRemaining(),
          );
          cleanupIds = (Array.isArray(tabs) ? tabs : [])
            .filter((tab) => tab?.url === fixtureUrl && Number.isInteger(tab?.id))
            .map((tab) => tab.id);
        }
        for (const tabId of cleanupIds) {
          await callbackResult(
            environment,
            (done) => environment.chrome.tabs.remove(tabId, done),
            cleanupRemaining(),
          );
        }
      } catch (_cleanupError) {
        // Cleanup was attempted for this run's exact marker only.
      }
    }
  },
  'windows-get-current-page': (environment) => callbackResult(
    environment,
    (done) => environment.chrome.windows.getCurrent(done),
  ),
});

export function createCase(descriptor, environment = globalThis) {
  return createRuntimeCase(descriptor, environment, CASE_IDS, SAFE_RUNNERS);
}
