import { callbackResult, createRuntimeCase } from '../probe-runtime.js';

export const CASE_IDS = Object.freeze([
  'content-settings-cookies-page',
  'cookies-get-worker',
  'history-search-worker',
  'identity-redirect-url-page',
  'idle-query-state-worker',
  'privacy-network-page',
  'web-auth-proxy-session-event-worker',
]);

const SAFE_RUNNERS = Object.freeze({
  'identity-redirect-url-page': (environment) => environment.chrome.identity.getRedirectURL('six-probe'),
  'idle-query-state-worker': (environment) => callbackResult(
    environment,
    (done) => environment.chrome.idle.queryState(15, done),
  ),
});

export function createCase(descriptor, environment = globalThis) {
  return createRuntimeCase(descriptor, environment, CASE_IDS, SAFE_RUNNERS);
}
