import { callbackResult, createRuntimeCase } from '../probe-runtime.js';

export const CASE_IDS = Object.freeze([
  'alarms-get-worker',
  'extension-incognito-access-worker',
  'i18n-ui-language-page',
  'permissions-contains-worker',
  'runtime-platform-info-worker',
  'storage-local-worker',
]);

function runAlarmsGet(environment) {
  return callbackResult(
    environment,
    (done) => environment.chrome.alarms.get('sixbridge-probe-missing-alarm', done),
  );
}

function runExtensionIncognitoAccess(environment) {
  return callbackResult(
    environment,
    (done) => environment.chrome.extension.isAllowedIncognitoAccess(done),
  );
}

function runI18nUiLanguage(environment) {
  return environment.chrome.i18n.getUILanguage();
}

function runPermissionsContains(environment) {
  return callbackResult(
    environment,
    (done) => environment.chrome.permissions.contains({ permissions: [] }, done),
  );
}

function runRuntimePlatformInfo(environment) {
  return callbackResult(
    environment,
    (done) => environment.chrome.runtime.getPlatformInfo(done),
  );
}

const SAFE_RUNNERS = Object.freeze({
  'alarms-get-worker': runAlarmsGet,
  'extension-incognito-access-worker': runExtensionIncognitoAccess,
  'i18n-ui-language-page': runI18nUiLanguage,
  'permissions-contains-worker': runPermissionsContains,
  'runtime-platform-info-worker': runRuntimePlatformInfo,
});

function resolveCase(environment, caseId) {
  switch (caseId) {
    case 'alarms-get-worker': return environment.chrome.alarms.get;
    case 'extension-incognito-access-worker': return environment.chrome.extension.isAllowedIncognitoAccess;
    case 'i18n-ui-language-page': return environment.chrome.i18n.getUILanguage;
    case 'permissions-contains-worker': return environment.chrome.permissions.contains;
    case 'runtime-platform-info-worker': return environment.chrome.runtime.getPlatformInfo;
    case 'storage-local-worker': return environment.chrome.storage.local;
    default: return undefined;
  }
}

export function createCase(descriptor, environment = globalThis) {
  return createRuntimeCase(descriptor, environment, CASE_IDS, SAFE_RUNNERS, resolveCase);
}
