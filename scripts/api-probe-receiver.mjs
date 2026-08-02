#!/usr/bin/env node

import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import {
  closeSync,
  constants,
  fstatSync,
  fsyncSync,
  linkSync,
  lstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  statSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const LOOPBACK = '127.0.0.1';
const DEFAULT_PORT = 47_171;
const MAX_BYTES = 1_048_576;
const MAX_RESULTS = 512;
const WORKER_OUTPUT_BYTES = 256;
const WORKER_STDERR_BYTES = 4_096;
const WORKER_TIMEOUT_MS = 5_000;
const ROOT_FIELDS = [
  'browserApkSetSha256',
  'browserPackage',
  'browserSignerSha256',
  'browserVersionCode',
  'browserVersionName',
  'catalogSha256',
  'extensionId',
  'groupId',
  'results',
  'schemaVersion',
  'sessionId',
  'wrapperSignerSha256',
];
const RESULT_FIELDS = [
  'caseId',
  'context',
  'detail',
  'durationMs',
  'member',
  'namespace',
  'runToken',
  'status',
];
const GROUP_IDS = new Set([
  'advanced-runtime',
  'base-runtime',
  'identity-privacy',
  'network',
  'tab-page',
  'user-interface',
]);
const STATUSES = new Set(['ABSENT', 'EXPOSED', 'FAILED', 'NOT_RUN', 'RESTRICTED', 'VERIFIED']);
const CONTEXTS = new Set([
  'content_script',
  'extension_page',
  'offscreen_document',
  'service_worker',
  'user_gesture',
]);
const ID = /^[a-z0-9][a-z0-9-]{0,63}$/;
const EXTENSION_ID = /^[a-p]{32}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const BROWSER_PACKAGE = /^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$/;
const CASE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const NAMESPACE = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*$/;
const MEMBER = /^[A-Za-z][A-Za-z0-9_]{0,127}$/;
const RUN_TOKEN = /^[A-Za-z0-9_-]{16,64}$/;
const ORIGIN = /^chrome-extension:\/\/[a-p]{32}$/;
const CONTENT_TYPE = /^application\/json(?:\s*;\s*charset=utf-8)?$/i;

function exactFields(value, expected) {
  return value && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).sort().join('\0') === expected.slice().sort().join('\0');
}

function rejectDuplicateKeys(text) {
  const stack = [];
  let stringStart = -1;
  let escaped = false;
  let depth = 0;
  for (let index = 0; index < text.length; index += 1) {
    const ch = text[index];
    if (stringStart >= 0) {
      if (escaped) escaped = false;
      else if (ch === '\\') escaped = true;
      else if (ch === '"') {
        let next = index + 1;
        while (next < text.length && /\s/.test(text[next])) next += 1;
        const keys = stack.at(-1);
        if (text[next] === ':' && keys instanceof Set) {
          const key = JSON.parse(text.slice(stringStart, index + 1));
          if (keys.has(key)) throw new TypeError('duplicate JSON field');
          keys.add(key);
        }
        stringStart = -1;
      }
    } else if (ch === '"') {
      stringStart = index;
    } else if (ch === '{') {
      depth += 1;
      if (depth > 64) throw new TypeError('JSON is too deep');
      stack.push(new Set());
    } else if (ch === '[') {
      depth += 1;
      if (depth > 64) throw new TypeError('JSON is too deep');
      stack.push(null);
    } else if (ch === '}' || ch === ']') {
      if (depth === 0 || stack.length === 0) throw new TypeError('invalid JSON nesting');
      depth -= 1;
      stack.pop();
    }
  }
  if (stringStart >= 0 || depth !== 0 || stack.length !== 0) throw new TypeError('invalid JSON structure');
}

function validateDetail(detail) {
  if (!detail || typeof detail !== 'object' || Array.isArray(detail)) {
    throw new TypeError('detail must be an object');
  }
  if (Buffer.byteLength(JSON.stringify(detail)) > 2_048) throw new TypeError('detail too large');
  let values = 0;
  const stack = [{ depth: 0, value: detail }];
  while (stack.length > 0) {
    const current = stack.pop();
    if (current.depth > 6) throw new TypeError('detail too deep');
    const value = current.value;
    if (value && typeof value === 'object') {
      if (!Array.isArray(value)) {
        for (const key of Object.keys(value)) {
          if (key.length > 128) throw new TypeError('detail key too large');
        }
      }
      const children = Array.isArray(value) ? value : Object.values(value);
      if (children.length > 64) throw new TypeError('too many detail values');
      values += children.length;
      if (values > 64) throw new TypeError('too many detail values');
      for (const child of children) stack.push({ depth: current.depth + 1, value: child });
    } else if (
      value !== null
      && typeof value !== 'string'
      && typeof value !== 'number'
      && typeof value !== 'boolean'
    ) {
      throw new TypeError('invalid detail value');
    } else if (typeof value === 'number' && (!Number.isFinite(value) || Math.abs(value) > 1e308)) {
      throw new TypeError('invalid detail number');
    } else if (typeof value === 'string' && value.length > 1_024) {
      throw new TypeError('detail string too large');
    }
  }
}

function validateStatusDetail(result) {
  const kind = {
    ABSENT: 'absent',
    EXPOSED: 'exposed',
    FAILED: 'error',
    NOT_RUN: 'gesture-required',
    RESTRICTED: 'error',
    VERIFIED: 'success',
  }[result.status];
  if (result.detail.kind !== kind) throw new TypeError('status and detail mismatch');
  if (kind === 'success') {
    if (!exactFields(result.detail, ['kind', 'valueType']) || !new Set([
        'bigint',
        'boolean',
        'function',
        'null',
        'number',
        'object',
        'string',
        'symbol',
        'undefined',
      ]).has(result.detail.valueType)) {
      throw new TypeError('invalid success detail');
    }
  } else if (kind === 'error') {
    if (!exactFields(result.detail, ['kind', 'message', 'name'])
      || typeof result.detail.message !== 'string'
      || result.detail.message.length > 512
      || typeof result.detail.name !== 'string'
      || result.detail.name.length < 1
      || result.detail.name.length > 80) {
      throw new TypeError('invalid error detail');
    }
  } else if (!exactFields(result.detail, ['kind'])) {
    throw new TypeError('invalid terminal detail');
  }
  if (result.status === 'NOT_RUN' && result.context !== 'user_gesture') {
    throw new TypeError('NOT_RUN requires user_gesture context');
  }
}

function parseEvidence(bytes, expectedExtensionId) {
  const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  rejectDuplicateKeys(text);
  const value = JSON.parse(text);
  if (!exactFields(value, ROOT_FIELDS)) throw new TypeError('invalid evidence fields');
  if (value.schemaVersion !== 1) throw new TypeError('invalid schema version');
  if (!ID.test(value.sessionId) || !ID.test(value.groupId) || !GROUP_IDS.has(value.groupId)) {
    throw new TypeError('invalid evidence ID');
  }
  if (!SHA256.test(value.catalogSha256)
    || !EXTENSION_ID.test(value.extensionId)
    || value.extensionId !== expectedExtensionId
    || !SHA256.test(value.wrapperSignerSha256)
    || !SHA256.test(value.browserApkSetSha256)
    || !SHA256.test(value.browserSignerSha256)) {
    throw new TypeError('invalid evidence digest');
  }
  if (!BROWSER_PACKAGE.test(value.browserPackage) || value.browserPackage.length > 200) {
    throw new TypeError('invalid browser package');
  }
  if (typeof value.browserVersionName !== 'string'
    || value.browserVersionName.length < 1
    || value.browserVersionName.length > 100
    || value.browserVersionName !== value.browserVersionName.trim()
    || !/^[\x20-\x7e]+$/.test(value.browserVersionName)) {
    throw new TypeError('invalid browser version name');
  }
  if (!Number.isSafeInteger(value.browserVersionCode) || value.browserVersionCode < 0) {
    throw new TypeError('invalid browser version code');
  }
  if (!Array.isArray(value.results) || value.results.length < 1 || value.results.length > MAX_RESULTS) {
    throw new TypeError('invalid results');
  }
  const cases = new Set();
  const keys = new Set();
  const runTokens = new Set();
  for (const result of value.results) {
    if (!exactFields(result, RESULT_FIELDS)
      || !CASE_ID.test(result.caseId)
      || !CONTEXTS.has(result.context)
      || !Number.isSafeInteger(result.durationMs)
      || result.durationMs < 0
      || result.durationMs > 600_000
      || !MEMBER.test(result.member)
      || !NAMESPACE.test(result.namespace)
      || !RUN_TOKEN.test(result.runToken)
      || !STATUSES.has(result.status)) {
      throw new TypeError('invalid result');
    }
    validateDetail(result.detail);
    validateStatusDetail(result);
    const key = `${result.namespace}\0${result.member}\0${result.context}`;
    if (cases.has(result.caseId) || keys.has(key) || runTokens.has(result.runToken)) {
      throw new TypeError('duplicate result');
    }
    cases.add(result.caseId);
    keys.add(key);
    runTokens.add(result.runToken);
  }
  return value;
}

function corsHeaders(origin) {
  return {
    'access-control-allow-headers': 'content-type',
    'access-control-allow-methods': 'POST, OPTIONS',
    'access-control-allow-origin': origin,
    vary: 'Origin',
  };
}

function respond(response, status, body = '', headers = {}) {
  if (response.writableEnded) return;
  const bytes = Buffer.from(body);
  response.writeHead(status, {
    'content-length': String(bytes.length),
    'content-type': 'text/plain; charset=utf-8',
    'x-content-type-options': 'nosniff',
    ...headers,
  });
  response.end(bytes);
}

class UnsafePathError extends Error {}
class EvidenceConflictError extends Error {}

const DIRECTORY_FLAGS = constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW;
const TEMP_FLAGS = constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW;
const WORKER_TOKEN = /^[0-9a-f]{32}$/;
const WORKER_IDENTITY = /^[0-9]{1,32}$/;
const WORKER_RESULTS = new Map([
  ['0:created', true],
  ['10:existing', false],
]);

function assertSafePlatform() {
  if (!Number.isInteger(constants.O_DIRECTORY) || constants.O_DIRECTORY <= 0
    || !Number.isInteger(constants.O_NOFOLLOW) || constants.O_NOFOLLOW <= 0) {
    throw new UnsafePathError('safe directory handles are not supported on this platform');
  }
}

function sameIdentity(left, right) {
  return left.dev === right.dev && left.ino === right.ino;
}

function validateOwnedDirectory(stat, label) {
  if (!stat.isDirectory()) throw new UnsafePathError(`unsafe ${label} directory`);
  if ((stat.mode & 0o022) !== 0) throw new UnsafePathError(`writable ${label} directory`);
  if (typeof process.getuid === 'function' && stat.uid !== process.getuid()) {
    throw new UnsafePathError(`foreign ${label} directory`);
  }
}

function validateOwnedFile(stat, label) {
  if (!stat.isFile() || stat.nlink !== 1) throw new UnsafePathError(`unsafe ${label} file`);
  if ((stat.mode & 0o022) !== 0) throw new UnsafePathError(`writable ${label} file`);
  if (typeof process.getuid === 'function' && stat.uid !== process.getuid()) {
    throw new UnsafePathError(`foreign ${label} file`);
  }
}

function openDirectory(directory, label) {
  let descriptor;
  try {
    descriptor = openSync(directory, DIRECTORY_FLAGS);
    const stat = fstatSync(descriptor);
    validateOwnedDirectory(stat, label);
    return { descriptor, stat };
  } catch (error) {
    if (descriptor !== undefined) closeSync(descriptor);
    if (error instanceof UnsafePathError) throw error;
    throw new UnsafePathError(`cannot open ${label} directory`);
  }
}

function existingTarget(targetName, bytes) {
  let stat;
  try {
    stat = lstatSync(targetName);
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
  if (stat.isSymbolicLink()) throw new UnsafePathError('unsafe evidence target');
  validateOwnedFile(stat, 'evidence target');
  if (readFileSync(targetName).equals(bytes)) return false;
  throw new EvidenceConflictError('different evidence already exists');
}

function assertDirectoryIdentity(rootDir, sessionId, root, session) {
  try {
    const currentRoot = lstatSync(rootDir);
    const currentSession = lstatSync(path.join(rootDir, sessionId));
    if (currentRoot.isSymbolicLink() || currentSession.isSymbolicLink()
      || !sameIdentity(currentRoot, root.stat) || !sameIdentity(currentSession, session.stat)
      || !sameIdentity(fstatSync(root.descriptor), root.stat)
      || !sameIdentity(fstatSync(session.descriptor), session.stat)) {
      throw new UnsafePathError('evidence directory identity changed');
    }
    validateOwnedDirectory(currentRoot, 'evidence root');
    validateOwnedDirectory(currentSession, 'evidence session');
  } catch (error) {
    if (error instanceof UnsafePathError) throw error;
    throw new UnsafePathError('cannot revalidate evidence directory');
  }
}

function identity(stat) {
  return { dev: String(stat.dev), ino: String(stat.ino) };
}

function sameExpectedIdentity(stat, expected) {
  return String(stat.dev) === expected.dev && String(stat.ino) === expected.ino;
}

function prepareStorage(rootDir, evidence) {
  try {
    mkdirSync(rootDir, { mode: 0o700 });
  } catch (error) {
    if (error.code !== 'EEXIST') throw error;
  }
  const root = openDirectory(rootDir, 'evidence root');
  let session;
  try {
    const sessionDir = path.join(rootDir, evidence.sessionId);
    try {
      mkdirSync(sessionDir, { mode: 0o700 });
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
    }
    session = openDirectory(sessionDir, 'evidence session');
    assertDirectoryIdentity(rootDir, evidence.sessionId, root, session);
    return {
      root: identity(root.stat),
      session: identity(session.stat),
      sessionDir,
    };
  } finally {
    if (session) closeSync(session.descriptor);
    closeSync(root.descriptor);
  }
}

function workerConfiguration() {
  const rootDir = String(process.env.SIX_PROBE_ROOT ?? '');
  const sessionDir = String(process.env.SIX_PROBE_SESSION ?? '');
  const sessionId = String(process.env.SIX_PROBE_SESSION_ID ?? '');
  const groupId = String(process.env.SIX_PROBE_GROUP_ID ?? '');
  const token = String(process.env.SIX_PROBE_TOKEN ?? '');
  const root = {
    dev: String(process.env.SIX_PROBE_ROOT_DEV ?? ''),
    ino: String(process.env.SIX_PROBE_ROOT_INO ?? ''),
  };
  const session = {
    dev: String(process.env.SIX_PROBE_SESSION_DEV ?? ''),
    ino: String(process.env.SIX_PROBE_SESSION_INO ?? ''),
  };
  if (!path.isAbsolute(rootDir) || path.resolve(rootDir) !== rootDir || rootDir.length > 4_096
    || !path.isAbsolute(sessionDir) || path.resolve(sessionDir) !== sessionDir || sessionDir.length > 4_096
    || !ID.test(sessionId) || !GROUP_IDS.has(groupId) || !WORKER_TOKEN.test(token)
    || sessionDir !== path.join(rootDir, sessionId)
    || !WORKER_IDENTITY.test(root.dev) || !WORKER_IDENTITY.test(root.ino)
    || !WORKER_IDENTITY.test(session.dev) || !WORKER_IDENTITY.test(session.ino)) {
    throw new TypeError('invalid storage worker configuration');
  }
  return { groupId, root, rootDir, session, sessionDir, sessionId, token };
}

async function readWorkerInput() {
  const chunks = [];
  let total = 0;
  for await (const chunk of process.stdin) {
    total += chunk.length;
    if (total > MAX_BYTES) throw new TypeError('storage worker input too large');
    chunks.push(chunk);
  }
  if (total < 1) throw new TypeError('storage worker input is empty');
  return Buffer.concat(chunks, total);
}

function saveEvidenceInWorker(config, root, session, bytes) {
  let tempName;
  let tempIdentity;
  let published = false;
  let completed = false;
  const targetName = `${config.groupId}.json`;
  try {
    assertDirectoryIdentity(config.rootDir, config.sessionId, root, session);
    const existing = existingTarget(targetName, bytes);
    if (existing !== null) return existing;

    tempName = `.${config.groupId}.${config.token}.tmp`;
    const tempDescriptor = openSync(tempName, TEMP_FLAGS, 0o600);
    try {
      const tempStat = fstatSync(tempDescriptor);
      validateOwnedFile(tempStat, 'evidence temp');
      tempIdentity = identity(tempStat);
      writeFileSync(tempDescriptor, bytes);
      fsyncSync(tempDescriptor);
    } finally {
      closeSync(tempDescriptor);
    }

    assertDirectoryIdentity(config.rootDir, config.sessionId, root, session);

    try {
      linkSync(tempName, targetName);
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
      const raced = existingTarget(targetName, bytes);
      if (raced === false) return false;
      throw new EvidenceConflictError('evidence target appeared while writing');
    }
    published = true;
    if (!sameExpectedIdentity(lstatSync(targetName), tempIdentity)) {
      throw new UnsafePathError('published evidence identity mismatch');
    }
    assertDirectoryIdentity(config.rootDir, config.sessionId, root, session);
    unlinkSync(tempName);
    tempName = undefined;
    const targetStat = lstatSync(targetName);
    validateOwnedFile(targetStat, 'evidence target');
    if (!sameExpectedIdentity(targetStat, tempIdentity)) {
      throw new UnsafePathError('evidence target was replaced');
    }
    fsyncSync(session.descriptor);
    completed = true;
    return true;
  } finally {
    let cleanupError;
    if (published && !completed) {
      try {
        const targetStat = lstatSync(targetName);
        if (!sameExpectedIdentity(targetStat, tempIdentity)) {
          throw new UnsafePathError('refusing to remove replaced evidence target');
        }
        unlinkSync(targetName);
        fsyncSync(session.descriptor);
      } catch (error) {
        if (error.code !== 'ENOENT') cleanupError = new UnsafePathError('evidence rollback failed');
      }
    }
    if (tempName) {
      try {
        const tempStat = lstatSync(tempName);
        if (!sameExpectedIdentity(tempStat, tempIdentity)) {
          throw new UnsafePathError('refusing to remove replaced evidence temp');
        }
        unlinkSync(tempName);
      } catch (error) {
        if (error.code !== 'ENOENT') cleanupError ??= error;
      }
    }
    if (cleanupError) throw cleanupError;
  }
}

function emitWorkerResult(status, code) {
  process.stdout.end(`${status}\n`, () => process.exit(code));
}

async function runStorageWorker() {
  let root;
  let session;
  try {
    assertSafePlatform();
    const config = workerConfiguration();
    if (!sameExpectedIdentity(statSync('.'), config.session)) {
      throw new UnsafePathError('storage worker cwd identity mismatch');
    }
    root = openDirectory(config.rootDir, 'evidence root');
    session = openDirectory('.', 'evidence session');
    if (!sameExpectedIdentity(root.stat, config.root)
      || !sameExpectedIdentity(session.stat, config.session)) {
      throw new UnsafePathError('storage worker directory identity mismatch');
    }
    assertDirectoryIdentity(config.rootDir, config.sessionId, root, session);
    const bytes = await readWorkerInput();
    const created = saveEvidenceInWorker(config, root, session, bytes);
    emitWorkerResult(created ? 'created' : 'existing', created ? 0 : 10);
  } catch (error) {
    if (error instanceof EvidenceConflictError) emitWorkerResult('conflict', 20);
    else if (error instanceof UnsafePathError) emitWorkerResult('unsafe', 21);
    else if (error instanceof TypeError) emitWorkerResult('invalid', 22);
    else emitWorkerResult('error', 23);
  } finally {
    if (session) closeSync(session.descriptor);
    if (root) closeSync(root.descriptor);
  }
}

function cleanupWorkerTemp(prepared, groupId, token) {
  let session;
  try {
    session = openDirectory(prepared.sessionDir, 'evidence session');
    if (!sameExpectedIdentity(session.stat, prepared.session)) return;
    const tempPath = path.join(prepared.sessionDir, `.${groupId}.${token}.tmp`);
    const stat = lstatSync(tempPath);
    if (!stat.isSymbolicLink() && stat.isFile()
      && (stat.mode & 0o022) === 0
      && (typeof process.getuid !== 'function' || stat.uid === process.getuid())) {
      unlinkSync(tempPath);
      fsyncSync(session.descriptor);
    }
  } catch {
    // Timeout cleanup is best effort and never follows a replaced session path.
  } finally {
    if (session) closeSync(session.descriptor);
  }
}

function saveEvidence(rootDir, evidence, bytes) {
  const prepared = prepareStorage(rootDir, evidence);
  const token = randomBytes(16).toString('hex');
  const workerPath = fileURLToPath(import.meta.url);
  return new Promise((resolve, reject) => {
    let stdout = Buffer.alloc(0);
    let stderrBytes = 0;
    let timedOut = false;
    let protocolOverflow = false;
    let forceKill;
    const worker = spawn(process.execPath, [workerPath, '--storage-worker'], {
      cwd: prepared.sessionDir,
      env: {
        SIX_PROBE_GROUP_ID: evidence.groupId,
        SIX_PROBE_ROOT: rootDir,
        SIX_PROBE_ROOT_DEV: prepared.root.dev,
        SIX_PROBE_ROOT_INO: prepared.root.ino,
        SIX_PROBE_SESSION: prepared.sessionDir,
        SIX_PROBE_SESSION_DEV: prepared.session.dev,
        SIX_PROBE_SESSION_ID: evidence.sessionId,
        SIX_PROBE_SESSION_INO: prepared.session.ino,
        SIX_PROBE_TOKEN: token,
      },
      shell: false,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    const timeout = setTimeout(() => {
      timedOut = true;
      worker.kill('SIGTERM');
      forceKill = setTimeout(() => worker.kill('SIGKILL'), 250);
    }, WORKER_TIMEOUT_MS);
    worker.stdout.on('data', (chunk) => {
      if (stdout.length + chunk.length > WORKER_OUTPUT_BYTES) {
        protocolOverflow = true;
        worker.kill('SIGKILL');
        return;
      }
      stdout = Buffer.concat([stdout, chunk]);
    });
    worker.stderr.on('data', (chunk) => {
      stderrBytes += chunk.length;
      if (stderrBytes > WORKER_STDERR_BYTES) {
        protocolOverflow = true;
        worker.kill('SIGKILL');
      }
    });
    worker.on('error', (error) => {
      clearTimeout(timeout);
      if (forceKill) clearTimeout(forceKill);
      reject(new UnsafePathError(`storage worker failed: ${error.code ?? 'unknown'}`));
    });
    worker.on('close', (code, signal) => {
      clearTimeout(timeout);
      if (forceKill) clearTimeout(forceKill);
      cleanupWorkerTemp(prepared, evidence.groupId, token);
      if (timedOut || protocolOverflow || signal) {
        reject(new UnsafePathError('storage worker did not terminate safely'));
        return;
      }
      const output = stdout.toString('utf8');
      const result = WORKER_RESULTS.get(`${code}:${output.trimEnd()}`);
      if (result !== undefined && output === `${output.trimEnd()}\n`) {
        resolve(result);
      } else if ((code === 20 && output === 'conflict\n') || (code === 21 && output === 'unsafe\n')) {
        reject(code === 20
          ? new EvidenceConflictError('evidence conflict')
          : new UnsafePathError('unsafe evidence path'));
      } else {
        reject(new UnsafePathError('invalid storage worker result'));
      }
    });
    worker.stdin.on('error', () => {});
    worker.stdin.end(bytes);
  });
}

export function createEvidenceReceiver({ expectedOrigin, rootDir }) {
  assertSafePlatform();
  if (!ORIGIN.test(String(expectedOrigin))) {
    throw new TypeError('expectedOrigin must be an exact chrome-extension origin');
  }
  const expectedExtensionId = expectedOrigin.slice('chrome-extension://'.length);
  const resolvedRoot = path.resolve(String(rootDir));
  const server = http.createServer((request, response) => {
    const origin = request.headers.origin;
    if (request.url !== '/evidence') {
      respond(response, 404, 'not found');
      request.resume();
      return;
    }
    if (origin !== expectedOrigin) {
      respond(response, 403, 'forbidden');
      request.resume();
      return;
    }
    const cors = corsHeaders(origin);
    if (request.method === 'OPTIONS') {
      const requestedMethod = request.headers['access-control-request-method'];
      const requestedHeaders = String(request.headers['access-control-request-headers'] ?? '')
        .split(',').map((value) => value.trim().toLowerCase()).filter(Boolean);
      if (requestedMethod !== 'POST' || requestedHeaders.some((value) => value !== 'content-type')) {
        respond(response, 403, 'forbidden', cors);
      } else {
        respond(response, 204, '', cors);
      }
      request.resume();
      return;
    }
    if (request.method !== 'POST') {
      respond(response, 405, 'method not allowed', { allow: 'POST, OPTIONS', ...cors });
      request.resume();
      return;
    }
    if (!CONTENT_TYPE.test(String(request.headers['content-type'] ?? ''))) {
      respond(response, 415, 'application/json required', cors);
      request.resume();
      return;
    }
    const declaredLength = Number(request.headers['content-length']);
    if (Number.isFinite(declaredLength) && declaredLength > MAX_BYTES) {
      respond(response, 413, 'payload too large', cors);
      request.resume();
      return;
    }
    const chunks = [];
    let total = 0;
    let rejected = false;
    request.on('data', (chunk) => {
      if (rejected) return;
      total += chunk.length;
      if (total > MAX_BYTES) {
        rejected = true;
        chunks.length = 0;
        respond(response, 413, 'payload too large', cors);
        return;
      }
      chunks.push(chunk);
    });
    request.on('error', () => {
      rejected = true;
      respond(response, 400, 'request error', cors);
    });
    request.on('end', async () => {
      if (rejected || response.writableEnded) return;
      const bytes = Buffer.concat(chunks);
      try {
        const evidence = parseEvidence(bytes, expectedExtensionId);
        const created = await saveEvidence(resolvedRoot, evidence, bytes);
        respond(response, created ? 201 : 200, created ? 'stored' : 'already stored', cors);
      } catch (error) {
        if (error instanceof EvidenceConflictError || error instanceof UnsafePathError) {
          respond(response, 409, 'evidence conflict', cors);
        } else {
          respond(response, 400, 'invalid evidence', cors);
        }
      }
    });
  });
  server.on('clientError', (_error, socket) => {
    if (socket.writable) {
      socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
    }
  });

  return {
    address() {
      return server.address();
    },
    close() {
      return new Promise((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
    },
    listen({ host = LOOPBACK, port = DEFAULT_PORT } = {}) {
      if (host !== LOOPBACK) return Promise.reject(new TypeError('receiver는 127.0.0.1에만 bind할 수 있습니다.'));
      return new Promise((resolve, reject) => {
        const onError = (error) => reject(error);
        server.once('error', onError);
        server.listen(port, LOOPBACK, () => {
          server.off('error', onError);
          resolve(server.address());
        });
      });
    },
  };
}

function parseArgs(argv) {
  const result = {
    expectedOrigin: process.env.SIXBRIDGE_PROBE_ORIGIN ?? '',
    port: DEFAULT_PORT,
    rootDir: path.resolve('.local/evidence/api-probe'),
  };
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (!value) throw new TypeError(`missing value for ${flag}`);
    if (flag === '--origin') result.expectedOrigin = value;
    else if (flag === '--port') result.port = Number(value);
    else if (flag === '--root') result.rootDir = path.resolve(value);
    else throw new TypeError(`unknown option: ${flag}`);
  }
  if (!Number.isInteger(result.port) || result.port < 1 || result.port > 65_535) {
    throw new TypeError('invalid port');
  }
  return result;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.length === 3 && process.argv[2] === '--storage-worker') {
    await runStorageWorker();
  } else {
    try {
      const options = parseArgs(process.argv.slice(2));
      const receiver = createEvidenceReceiver(options);
      await receiver.listen({ port: options.port });
      process.stdout.write(`SIX API probe receiver: http://${LOOPBACK}:${options.port}/evidence\n`);
    } catch (error) {
      process.stderr.write(`${error.message}\n`);
      process.exitCode = 1;
    }
  }
}
