#!/usr/bin/env node

import { randomBytes } from 'node:crypto';
import {
  lstat,
  mkdir,
  open,
  readFile,
  rename,
  rm,
  unlink,
} from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const LOOPBACK = '127.0.0.1';
const DEFAULT_PORT = 47_171;
const MAX_BYTES = 1_048_576;
const MAX_RESULTS = 512;
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
    } else if (typeof value === 'number' && !Number.isFinite(value)) {
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
    if (!new Set([
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
    if (typeof result.detail.message !== 'string'
      || result.detail.message.length > 512
      || typeof result.detail.name !== 'string'
      || result.detail.name.length < 1
      || result.detail.name.length > 80) {
      throw new TypeError('invalid error detail');
    }
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

async function requireDirectory(directory, create = false) {
  if (create) await mkdir(directory, { recursive: false, mode: 0o700 }).catch((error) => {
    if (error.code !== 'EEXIST') throw error;
  });
  const stat = await lstat(directory);
  if (stat.isSymbolicLink() || !stat.isDirectory()) throw new UnsafePathError('unsafe evidence directory');
}

class UnsafePathError extends Error {}
class EvidenceConflictError extends Error {}

async function acquireLock(lockPath) {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    try {
      return await open(lockPath, 'wx', 0o600);
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
      const stat = await lstat(lockPath).catch((cause) => {
        if (cause.code === 'ENOENT') return null;
        throw cause;
      });
      if (stat && (stat.isSymbolicLink() || !stat.isFile())) {
        throw new UnsafePathError('unsafe evidence lock');
      }
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
  }
  throw new EvidenceConflictError('evidence group is busy');
}

async function saveEvidence(rootDir, evidence, bytes) {
  await requireDirectory(rootDir, true);
  const sessionDir = path.join(rootDir, evidence.sessionId);
  await requireDirectory(sessionDir, true);
  const target = path.join(sessionDir, `${evidence.groupId}.json`);
  const lockPath = path.join(sessionDir, `${evidence.groupId}.lock`);
  const lock = await acquireLock(lockPath);
  let tempPath;
  try {
    const stat = await lstat(target).catch((error) => {
      if (error.code === 'ENOENT') return null;
      throw error;
    });
    if (stat) {
      if (stat.isSymbolicLink() || !stat.isFile()) throw new UnsafePathError('unsafe evidence target');
      const existing = await readFile(target);
      if (existing.equals(bytes)) return false;
      throw new EvidenceConflictError('different evidence already exists');
    }
    tempPath = path.join(sessionDir, `.${evidence.groupId}.${process.pid}.${randomBytes(8).toString('hex')}.tmp`);
    const temp = await open(tempPath, 'wx', 0o600);
    try {
      await temp.writeFile(bytes);
      await temp.sync();
    } finally {
      await temp.close();
    }
    const appeared = await lstat(target).catch((error) => {
      if (error.code === 'ENOENT') return null;
      throw error;
    });
    if (appeared) throw new EvidenceConflictError('evidence target appeared while writing');
    await rename(tempPath, target);
    tempPath = undefined;
    const directory = await open(sessionDir, 'r');
    try {
      await directory.sync();
    } finally {
      await directory.close();
    }
    return true;
  } finally {
    if (tempPath) await rm(tempPath, { force: true });
    await lock.close();
    await unlink(lockPath).catch((error) => {
      if (error.code !== 'ENOENT') throw error;
    });
  }
}

export function createEvidenceReceiver({ expectedOrigin, rootDir }) {
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
