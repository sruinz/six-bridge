import { callbackCompletion, callbackResult, createRuntimeCase } from '../probe-runtime.js';

export const CASE_IDS = Object.freeze([
  'action-get-title-page',
  'commands-get-all-page',
  'context-menus-create-worker',
  'notifications-permission-level-worker',
  'side-panel-get-options-page',
  'tts-get-voices-page',
]);

const SAFE_RUNNERS = Object.freeze({
  'action-get-title-page': (environment) => callbackResult(
    environment,
    (done) => environment.chrome.action.getTitle({}, done),
  ),
  'commands-get-all-page': (environment) => callbackResult(
    environment,
    (done) => environment.chrome.commands.getAll(done),
  ),
  'context-menus-create-worker': async (environment, cleanup) => {
    if (cleanup?.member !== 'remove' || cleanup?.target !== 'fixture_id' || !cleanup.fixtureId) {
      throw new Error('context menu cleanup contract mismatch');
    }
    const fixtureId = cleanup.fixtureId;
    let created = false;
    try {
      await callbackCompletion(environment, (done) => {
        environment.chrome.contextMenus.create(
          { contexts: ['all'], id: fixtureId, title: 'SIX API Probe' },
          done,
        );
      });
      created = true;
      return { created: true };
    } finally {
      if (created) {
        await callbackResult(
          environment,
          (done) => environment.chrome.contextMenus.remove(fixtureId, done),
        );
      }
    }
  },
  'notifications-permission-level-worker': (environment) => callbackResult(
    environment,
    (done) => environment.chrome.notifications.getPermissionLevel(done),
  ),
  'side-panel-get-options-page': (environment) => environment.chrome.sidePanel.getOptions({}),
  'tts-get-voices-page': (environment) => callbackResult(
    environment,
    (done) => environment.chrome.tts.getVoices(done),
  ),
});

export function createCase(descriptor, environment = globalThis) {
  return createRuntimeCase(descriptor, environment, CASE_IDS, SAFE_RUNNERS);
}
