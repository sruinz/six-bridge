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

function runIdentityRedirectUrl(environment) {
  return environment.chrome.identity.getRedirectURL('six-probe');
}

function runIdleQueryState(environment) {
  return callbackResult(
    environment,
    (done) => environment.chrome.idle.queryState(15, done),
  );
}

const SAFE_RUNNERS = Object.freeze({
  'identity-redirect-url-page': runIdentityRedirectUrl,
  'idle-query-state-worker': runIdleQueryState,
});

function resolveCase(environment, caseId) {
  switch (caseId) {
    case 'content-settings-cookies-page': return environment.chrome.contentSettings.cookies;
    case 'cookies-get-worker': return environment.chrome.cookies.get;
    case 'history-search-worker': return environment.chrome.history.search;
    case 'identity-redirect-url-page': return environment.chrome.identity.getRedirectURL;
    case 'idle-query-state-worker': return environment.chrome.idle.queryState;
    case 'privacy-network-page': return environment.chrome.privacy.network;
    case 'web-auth-proxy-session-event-worker': return environment.chrome.webAuthenticationProxy.onRemoteSessionStateChange;
    default: return undefined;
  }
}

export function createCase(descriptor, environment = globalThis) {
  return createRuntimeCase(descriptor, environment, CASE_IDS, SAFE_RUNNERS, resolveCase);
}
