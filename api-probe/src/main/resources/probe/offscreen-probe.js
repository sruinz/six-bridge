import { runCase } from './probe-runtime.js';
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

globalThis.chrome?.runtime?.onMessage?.addListener((message, _sender, sendResponse) => {
  if (message?.type !== 'six-probe-run' || message.bridgeTarget !== 'offscreen' || message.descriptor?.context !== 'offscreen_document') {
    return false;
  }
  const factory = FACTORIES[message.groupId];
  if (!factory || message.descriptor.id !== message.caseId) return false;
  runCase(factory(message.descriptor, globalThis)).then((result) => {
    sendResponse({ result, type: 'six-probe-result' });
  });
  return true;
});
