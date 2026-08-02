import {
  callbackResult,
  failedResult,
  runCase,
} from './probe-runtime.js';
import { createCase as createAdvanced } from './cases/advanced-runtime.js';
import { createCase as createBase } from './cases/base-runtime.js';
import { createCase as createIdentity } from './cases/identity-privacy.js';
import { createCase as createNetwork } from './cases/network.js';
import { createCase as createTabPage } from './cases/tab-page.js';
import { createCase as createUserInterface } from './cases/user-interface.js';

const FACTORIES = Object.freeze({
  'advanced-runtime': createAdvanced,
  'base-runtime': createBase,
  'identity-privacy': createIdentity,
  network: createNetwork,
  'tab-page': createTabPage,
  'user-interface': createUserInterface,
});

async function sendResult(descriptor, runToken, result) {
  const ack = await callbackResult(
    globalThis,
    (done) => globalThis.chrome.runtime.sendMessage(
      { result, type: 'six-probe-result' },
      done,
    ),
    1_000,
  );
  if (
    ack?.type !== 'six-probe-ack'
    || ack.caseId !== descriptor.id
    || ack.runToken !== runToken
  ) {
    throw new Error('offscreen probe acknowledgement mismatch');
  }
}

async function execute(message, factory) {
  const descriptor = message.descriptor;
  let result;
  try {
    result = await runCase(
      factory(descriptor, globalThis),
      message.runToken,
      8_000,
    );
  } catch (error) {
    result = failedResult(descriptor, message.runToken, error);
  }
  try {
    await sendResult(descriptor, message.runToken, result);
  } catch {
    // The service worker already expired or rejected this execution.
  }
}

globalThis.chrome?.runtime?.onMessage?.addListener((message, _sender, sendResponse) => {
  if (
    message?.type !== 'six-probe-run'
    || message.bridgeTarget !== 'offscreen'
    || message.descriptor?.context !== 'offscreen_document'
  ) {
    return false;
  }
  const factory = FACTORIES[message.groupId];
  if (!factory || message.descriptor.id !== message.caseId || !message.runToken) return false;
  sendResponse({
    caseId: message.caseId,
    runToken: message.runToken,
    type: 'six-probe-dispatched',
  });
  void execute(message, factory);
  return false;
});
