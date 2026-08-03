import { callbackResult, createRuntimeCase } from '../probe-runtime.js';

export const CASE_IDS = Object.freeze([
  'debugger-attach-worker',
  'management-get-self-worker',
  'offscreen-has-document-worker',
  'scripting-get-registered-worker',
  'user-scripts-get-scripts-worker',
]);

function runManagementGetSelf(environment) {
  return callbackResult(
    environment,
    (done) => environment.chrome.management.getSelf(done),
  );
}

function runOffscreenHasDocument(environment) {
  return environment.chrome.offscreen.hasDocument();
}

function runScriptingGetRegistered(environment) {
  return environment.chrome.scripting.getRegisteredContentScripts();
}

function runUserScriptsGetScripts(environment) {
  return environment.chrome.userScripts.getScripts();
}

const SAFE_RUNNERS = Object.freeze({
  'management-get-self-worker': runManagementGetSelf,
  'offscreen-has-document-worker': runOffscreenHasDocument,
  'scripting-get-registered-worker': runScriptingGetRegistered,
  'user-scripts-get-scripts-worker': runUserScriptsGetScripts,
});

function resolveCase(environment, caseId) {
  switch (caseId) {
    case 'debugger-attach-worker': return environment.chrome.debugger.attach;
    case 'management-get-self-worker': return environment.chrome.management.getSelf;
    case 'offscreen-has-document-worker': return environment.chrome.offscreen.hasDocument;
    case 'scripting-get-registered-worker': return environment.chrome.scripting.getRegisteredContentScripts;
    case 'user-scripts-get-scripts-worker': return environment.chrome.userScripts.getScripts;
    default: return undefined;
  }
}

export function createCase(descriptor, environment = globalThis) {
  return createRuntimeCase(descriptor, environment, CASE_IDS, SAFE_RUNNERS, resolveCase);
}
