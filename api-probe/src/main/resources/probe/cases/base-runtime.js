import { callbackResult, createRuntimeCase } from '../probe-runtime.js';

export const CASE_IDS = Object.freeze([
  'alarms-get-worker',
  'extension-incognito-access-worker',
  'i18n-ui-language-page',
  'permissions-contains-worker',
  'runtime-platform-info-worker',
  'storage-local-worker',
]);

const SAFE_RUNNERS = Object.freeze({
  'alarms-get-worker': (environment) => callbackResult(
    environment,
    (done) => environment.chrome.alarms.get('sixbridge-probe-missing-alarm', done),
  ),
  'extension-incognito-access-worker': (environment) => callbackResult(
    environment,
    (done) => environment.chrome.extension.isAllowedIncognitoAccess(done),
  ),
  'i18n-ui-language-page': (environment) => environment.chrome.i18n.getUILanguage(),
  'permissions-contains-worker': (environment) => callbackResult(
    environment,
    (done) => environment.chrome.permissions.contains({ permissions: [] }, done),
  ),
  'runtime-platform-info-worker': (environment) => callbackResult(
    environment,
    (done) => environment.chrome.runtime.getPlatformInfo(done),
  ),
});

export function createCase(descriptor, environment = globalThis) {
  return createRuntimeCase(descriptor, environment, CASE_IDS, SAFE_RUNNERS);
}
