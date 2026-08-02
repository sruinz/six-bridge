import { callbackResult, createRuntimeCase } from '../probe-runtime.js';

export const CASE_IDS = Object.freeze([
  'debugger-attach-worker',
  'management-get-self-worker',
  'offscreen-has-document-worker',
  'scripting-get-registered-worker',
  'user-scripts-get-scripts-worker',
]);

const SAFE_RUNNERS = Object.freeze({
  'management-get-self-worker': (environment) => callbackResult(
    environment,
    (done) => environment.chrome.management.getSelf(done),
  ),
  'offscreen-has-document-worker': (environment) => environment.chrome.offscreen.hasDocument(),
  'scripting-get-registered-worker': (environment) => (
    environment.chrome.scripting.getRegisteredContentScripts()
  ),
  'user-scripts-get-scripts-worker': (environment) => environment.chrome.userScripts.getScripts(),
});

export function createCase(descriptor, environment = globalThis) {
  return createRuntimeCase(descriptor, environment, CASE_IDS, SAFE_RUNNERS);
}
