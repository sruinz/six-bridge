import { callbackResult, createRuntimeCase } from '../probe-runtime.js';

const FIXTURE_URL = 'https://example.com/';

export const CASE_IDS = Object.freeze([
  'search-query-page',
  'tab-groups-query-page',
  'tabs-create-fixture-page',
  'windows-get-current-page',
]);

const SAFE_RUNNERS = Object.freeze({
  'tabs-create-fixture-page': async (environment, cleanup) => {
    if (cleanup?.member !== 'remove' || cleanup?.target !== 'created_resource_id') {
      throw new Error('tabs cleanup contract mismatch');
    }
    let createdTabId;
    try {
      const tab = await callbackResult(
        environment,
        (done) => environment.chrome.tabs.create({ active: false, url: FIXTURE_URL }, done),
      );
      createdTabId = tab?.id;
      if (!Number.isInteger(createdTabId)) throw new Error('created tab ID is missing');
      return { created: true };
    } finally {
      if (Number.isInteger(createdTabId)) {
        await callbackResult(
          environment,
          (done) => environment.chrome.tabs.remove(createdTabId, done),
        );
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
