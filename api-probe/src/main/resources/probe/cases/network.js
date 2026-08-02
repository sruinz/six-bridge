import { createRuntimeCase } from '../probe-runtime.js';

export const CASE_IDS = Object.freeze([
  'declarative-content-on-page-changed-worker',
  'declarative-net-request-static-count-worker',
  'proxy-settings-worker',
  'web-navigation-get-all-frames-worker',
  'web-request-before-request-worker',
]);

const SAFE_RUNNERS = Object.freeze({
  'declarative-net-request-static-count-worker': (environment) => (
    environment.chrome.declarativeNetRequest.getAvailableStaticRuleCount()
  ),
});

export function createCase(descriptor, environment = globalThis) {
  return createRuntimeCase(descriptor, environment, CASE_IDS, SAFE_RUNNERS);
}
