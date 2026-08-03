import { createRuntimeCase } from '../probe-runtime.js';

export const CASE_IDS = Object.freeze([
  'declarative-content-on-page-changed-worker',
  'declarative-net-request-static-count-worker',
  'proxy-settings-worker',
  'web-navigation-get-all-frames-worker',
  'web-request-before-request-worker',
]);

function runDeclarativeNetRequestStaticCount(environment) {
  return environment.chrome.declarativeNetRequest.getAvailableStaticRuleCount();
}

const SAFE_RUNNERS = Object.freeze({
  'declarative-net-request-static-count-worker': runDeclarativeNetRequestStaticCount,
});

function resolveCase(environment, caseId) {
  switch (caseId) {
    case 'declarative-content-on-page-changed-worker': return environment.chrome.declarativeContent.onPageChanged;
    case 'declarative-net-request-static-count-worker': return environment.chrome.declarativeNetRequest.getAvailableStaticRuleCount;
    case 'proxy-settings-worker': return environment.chrome.proxy.settings;
    case 'web-navigation-get-all-frames-worker': return environment.chrome.webNavigation.getAllFrames;
    case 'web-request-before-request-worker': return environment.chrome.webRequest.onBeforeRequest;
    default: return undefined;
  }
}

export function createCase(descriptor, environment = globalThis) {
  return createRuntimeCase(descriptor, environment, CASE_IDS, SAFE_RUNNERS, resolveCase);
}
