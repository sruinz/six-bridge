import { callbackCompletion, callbackResult, createRuntimeCase } from '../probe-runtime.js';

export const CASE_IDS = Object.freeze([
  'action-get-title-page',
  'commands-get-all-page',
  'context-menus-create-worker',
  'notifications-permission-level-worker',
  'side-panel-get-options-page',
  'tts-get-voices-page',
]);

function runActionGetTitle(environment) {
  return callbackResult(
    environment,
    (done) => environment.chrome.action.getTitle({}, done),
  );
}

function runCommandsGetAll(environment) {
  return callbackResult(
    environment,
    (done) => environment.chrome.commands.getAll(done),
  );
}

async function runContextMenusCreate(environment, cleanup, _runToken, timeoutMs) {
  if (cleanup?.member !== 'remove' || cleanup?.target !== 'fixture_id' || !cleanup.fixtureId) {
    throw new Error('context menu cleanup contract mismatch');
  }
  const fixtureId = cleanup.fixtureId;
  const deadline = Date.now() + timeoutMs;
  const remaining = () => Math.max(1, deadline - Date.now());
  let createdId;
  try {
    await callbackCompletion(environment, (done) => {
      createdId = environment.chrome.contextMenus.create(
        { contexts: ['all'], id: fixtureId, title: 'SIX API Probe' },
        done,
      );
    }, remaining());
    if (createdId !== fixtureId) throw new Error('created context menu ID mismatch');
    return { created: true };
  } finally {
    if (createdId === fixtureId) {
      try {
        await callbackResult(
          environment,
          (done) => environment.chrome.contextMenus.remove(fixtureId, done),
          remaining(),
        );
      } catch (_cleanupError) {
        // The probe result remains the original failure; cleanup was still attempted.
      }
    }
  }
}

function runNotificationsPermissionLevel(environment) {
  return callbackResult(
    environment,
    (done) => environment.chrome.notifications.getPermissionLevel(done),
  );
}

function runSidePanelGetOptions(environment) {
  return environment.chrome.sidePanel.getOptions({});
}

function runTtsGetVoices(environment) {
  return callbackResult(
    environment,
    (done) => environment.chrome.tts.getVoices(done),
  );
}

const SAFE_RUNNERS = Object.freeze({
  'action-get-title-page': runActionGetTitle,
  'commands-get-all-page': runCommandsGetAll,
  'context-menus-create-worker': runContextMenusCreate,
  'notifications-permission-level-worker': runNotificationsPermissionLevel,
  'side-panel-get-options-page': runSidePanelGetOptions,
  'tts-get-voices-page': runTtsGetVoices,
});

function resolveCase(environment, caseId) {
  switch (caseId) {
    case 'action-get-title-page': return environment.chrome.action.getTitle;
    case 'commands-get-all-page': return environment.chrome.commands.getAll;
    case 'context-menus-create-worker': return environment.chrome.contextMenus.create;
    case 'notifications-permission-level-worker': return environment.chrome.notifications.getPermissionLevel;
    case 'side-panel-get-options-page': return environment.chrome.sidePanel.getOptions;
    case 'tts-get-voices-page': return environment.chrome.tts.getVoices;
    default: throw new TypeError('unknown user-interface probe case');
  }
}

export function createCase(descriptor, environment = globalThis) {
  return createRuntimeCase(descriptor, environment, CASE_IDS, SAFE_RUNNERS, resolveCase);
}
