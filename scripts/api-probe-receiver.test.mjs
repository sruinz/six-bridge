import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import {
  access,
  chmod,
  link,
  mkdtemp,
  mkdir,
  readFile,
  readdir,
  rename,
  symlink,
  writeFile,
} from 'node:fs/promises';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  createEvidenceReceiver,
  createSessionEvidenceReceiver,
  evidenceReceiverTestHooks,
} from './api-probe-receiver.mjs';

const ORIGIN = 'chrome-extension://abcdefghijklmnopabcdefghijklmnop';
const SHA_A = 'a'.repeat(64);
const SHA_B = 'b'.repeat(64);
const SHA_C = 'c'.repeat(64);
const SHA_D = 'd'.repeat(64);
const GROUP_IDS = [
  'advanced-runtime', 'base-runtime', 'identity-privacy',
  'network', 'tab-page', 'user-interface',
];

function sessionConfig() {
  return {
    schemaVersion: 1,
    sessionId: 'session-20260802',
    catalogSha256: SHA_A,
    browserPackage: 'com.sec.android.app.sbrowser',
    browserVersionName: '30.0.2.61',
    browserVersionCode: 300020061,
    browserApkSetSha256: SHA_C,
    browserSignerSha256: SHA_D,
    bindings: GROUP_IDS.map((groupId, index) => ({
      groupId,
      extensionId: String.fromCharCode('a'.charCodeAt(0) + index).repeat(32),
      wrapperSignerSha256: SHA_B,
    })),
  };
}

function submission(groupId, overrides = {}) {
  return {
    schemaVersion: 1,
    groupId,
    catalogSha256: SHA_A,
    results: [{
      caseId: `${groupId}-case`,
      context: 'extension_page',
      detail: { kind: 'exposed' },
      durationMs: 1,
      member: 'getURL',
      namespace: 'runtime',
      runToken: `run-token-${groupId.replaceAll('-', '').padEnd(16, '0').slice(0, 16)}`,
      status: 'EXPOSED',
    }],
    ...overrides,
  };
}

async function sessionFixture() {
  const rootDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-session-receiver-'));
  const receiver = createSessionEvidenceReceiver({ sessionConfig: sessionConfig(), rootDir });
  await receiver.listen({ host: '127.0.0.1', port: 0 });
  return { baseUrl: `http://127.0.0.1:${receiver.address().port}`, receiver, rootDir };
}

function evidence(overrides = {}) {
  return {
    schemaVersion: 1,
    sessionId: 'session-20260802',
    groupId: 'base-runtime',
    catalogSha256: SHA_A,
    extensionId: 'abcdefghijklmnopabcdefghijklmnop',
    wrapperSignerSha256: SHA_B,
    browserPackage: 'com.sec.android.app.sbrowser',
    browserVersionName: '30.0.2.61',
    browserVersionCode: 300020061,
    browserApkSetSha256: SHA_C,
    browserSignerSha256: SHA_D,
    results: [{
      caseId: 'runtime.getPlatformInfo',
      context: 'service_worker',
      detail: { kind: 'success', valueType: 'object' },
      durationMs: 1,
      member: 'getPlatformInfo',
      namespace: 'runtime',
      runToken: 'run-token-00000001',
      status: 'VERIFIED',
    }],
    ...overrides,
  };
}

async function fixture() {
  const rootDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-receiver-'));
  const receiver = createEvidenceReceiver({ expectedOrigin: ORIGIN, rootDir });
  await receiver.listen({ host: '127.0.0.1', port: 0 });
  return {
    baseUrl: `http://127.0.0.1:${receiver.address().port}`,
    receiver,
    rootDir,
  };
}

function request(baseUrl, { body, headers = {}, method = 'POST', pathname = '/evidence' }) {
  const url = new URL(pathname, baseUrl);
  return new Promise((resolve, reject) => {
    const req = http.request(url, { headers, method }, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => resolve({
        body: Buffer.concat(chunks).toString('utf8'),
        headers: res.headers,
        status: res.statusCode,
      }));
    });
    req.on('error', reject);
    if (body !== undefined) req.write(body);
    req.end();
  });
}

function post(baseUrl, value, extra = {}) {
  const body = typeof value === 'string' || Buffer.isBuffer(value)
    ? value
    : Buffer.from(JSON.stringify(value));
  return request(baseUrl, {
    body,
    headers: {
      'content-length': String(Buffer.byteLength(body)),
      'content-type': 'application/json; charset=utf-8',
      origin: ORIGIN,
      ...(extra.headers ?? {}),
    },
    pathname: extra.pathname,
  });
}

function sessionPost(baseUrl, origin, value, extra = {}) {
  const body = typeof value === 'string' || Buffer.isBuffer(value)
    ? value : Buffer.from(`${JSON.stringify(canonicalTestValue(value))}\n`);
  return request(baseUrl, {
    body,
    headers: {
      'content-length': String(Buffer.byteLength(body)),
      'content-type': 'application/json; charset=utf-8',
      origin,
      ...(extra.headers ?? {}),
    },
    method: extra.method ?? 'POST',
    pathname: extra.pathname,
  });
}

function canonicalTestValue(value) {
  if (Array.isArray(value)) return value.map(canonicalTestValue);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonicalTestValue(value[key])]));
  }
  return value;
}

async function loadRejectCorpus() {
  const bytes = await readFile(new URL(
    '../api-probe/src/test/resources/evidence/reject-corpus.json',
    import.meta.url,
  ));
  return JSON.parse(bytes).cases;
}

function applyCorpusOperation(operation) {
  const value = structuredClone(evidence());
  const first = value.results[0];
  if (operation === 'detail-key-129') first.detail['k'.repeat(129)] = true;
  else if (operation === 'detail-huge-finite-number') first.detail.number = 1.5e308;
  else if (operation === 'unsafe-browser-version-code') value.browserVersionCode = 9_007_199_254_740_992;
  else if (operation === 'duplicate-case-key') value.results.push(structuredClone(first));
  else if (operation === 'unknown-root-field') value.unknown = true;
  else if (operation === 'unknown-result-field') first.unknown = true;
  else if (operation === 'unknown-detail-field') first.detail.unknown = true;
  else if (operation === 'detail-depth-seven') {
    first.detail.nested = { a: { b: { c: { d: { e: { f: { g: true } } } } } } };
  } else if (operation === 'status-detail-mismatch') first.status = 'ABSENT';
  else if (operation === 'not-run-worker-context') {
    first.status = 'NOT_RUN';
    first.detail = { kind: 'gesture-required' };
  } else throw new Error(`unknown corpus operation: ${operation}`);
  return value;
}

function allTerminalEvidence() {
  const descriptors = [
    ['absent', 'service_worker', { kind: 'absent' }, 'ABSENT'],
    ['exposed', 'extension_page', { kind: 'exposed' }, 'EXPOSED'],
    ['failed', 'service_worker', { kind: 'error', message: 'boom', name: 'Error' }, 'FAILED'],
    ['gesture', 'user_gesture', { kind: 'gesture-required' }, 'NOT_RUN'],
    ['restricted', 'service_worker', { kind: 'error', message: 'denied', name: 'Error' }, 'RESTRICTED'],
    ['verified', 'offscreen_document', { kind: 'success', valueType: 'object' }, 'VERIFIED'],
  ];
  return evidence({
    results: descriptors.map(([suffix, context, detail, status], index) => ({
      caseId: `case-${suffix}`,
      context,
      detail,
      durationMs: 0,
      member: `member${index}`,
      namespace: `namespace${index}`,
      runToken: `run-token-${String(index + 1).padStart(8, '0')}`,
      status,
    })),
  });
}

function chunkedOversizePost(baseUrl) {
  return new Promise((resolve, reject) => {
    const req = http.request(new URL('/evidence', baseUrl), {
      headers: { 'content-type': 'application/json', origin: ORIGIN },
      method: 'POST',
    }, (res) => {
      res.resume();
      res.on('end', () => resolve(res.statusCode));
    });
    req.on('error', reject);
    const chunk = Buffer.alloc(64 * 1024, 0x20);
    for (let index = 0; index < 17; index += 1) req.write(chunk);
    req.end();
  });
}

function rawHttp(baseUrl, contentLength, body) {
  const url = new URL(baseUrl);
  return new Promise((resolve, reject) => {
    const socket = net.createConnection(Number(url.port), url.hostname);
    const chunks = [];
    socket.setTimeout(3_000, () => socket.destroy(new Error('raw HTTP timeout')));
    socket.on('error', reject);
    socket.on('data', (chunk) => chunks.push(chunk));
    socket.on('close', () => {
      const response = Buffer.concat(chunks).toString('latin1');
      const match = response.match(/^HTTP\/1\.1 (\d{3})/);
      if (match) resolve(Number(match[1]));
      else reject(new Error(`raw HTTP response missing: ${response}`));
    });
    socket.on('connect', () => {
      socket.end(
        `POST /evidence HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: ${ORIGIN}\r\n`
        + `Content-Type: application/json\r\nContent-Length: ${contentLength}\r\nConnection: close\r\n\r\n${body}`,
      );
    });
  });
}

function startRaceChild(script, argumentsList) {
  const child = spawn(process.execPath, ['-e', script, ...argumentsList], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let error = '';
  child.stderr.on('data', (chunk) => { error += chunk.toString(); });
  const ready = new Promise((resolve, reject) => {
    child.stdout.once('data', (chunk) => {
      if (chunk.toString().includes('ready')) resolve();
      else reject(new Error(`race child did not become ready: ${chunk}`));
    });
    child.once('exit', (code) => {
      if (code !== 0) reject(new Error(`race child exited before ready (${code}): ${error}`));
    });
  });
  return {
    child,
    ready,
    completed: new Promise((resolve, reject) => child.on('exit', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`race child failed (${code}): ${error}`));
    })),
  };
}

function startCwdHarness(cwd, rootDir) {
  const receiverModule = new URL('./api-probe-receiver.mjs', import.meta.url).href;
  const child = spawn(process.execPath, ['-e', `
    const fs = require('node:fs');
    (async () => {
      const originalChdir = process.chdir.bind(process);
      let chdirCalls = 0;
      process.chdir = (directory) => { chdirCalls += 1; return originalChdir(directory); };
      const before = fs.statSync('.');
      const { createEvidenceReceiver } = await import(process.env.RECEIVER_MODULE);
      const receiver = createEvidenceReceiver({
        expectedOrigin: process.env.RECEIVER_ORIGIN,
        rootDir: process.env.RECEIVER_ROOT,
      });
      await receiver.listen({ host: '127.0.0.1', port: 0 });
      process.send({ port: receiver.address().port, type: 'ready' });
      process.on('message', async (message) => {
        if (message !== 'report') return;
        const after = fs.statSync('.');
        process.send({
          chdirCalls,
          identityStable: before.dev === after.dev && before.ino === after.ino,
          type: 'report',
        });
        await receiver.close();
        process.exit(0);
      });
    })().catch((error) => { console.error(error); process.exit(1); });
  `], {
    cwd,
    env: {
      ...process.env,
      RECEIVER_MODULE: receiverModule,
      RECEIVER_ORIGIN: ORIGIN,
      RECEIVER_ROOT: rootDir,
    },
    stdio: ['ignore', 'ignore', 'pipe', 'ipc'],
  });
  let error = '';
  child.stderr.on('data', (chunk) => { error += chunk.toString(); });
  const message = (type) => new Promise((resolve, reject) => {
    const onMessage = (value) => {
      if (value?.type !== type) return;
      cleanup();
      resolve(value);
    };
    const onExit = (code) => {
      cleanup();
      reject(new Error(`cwd harness exited (${code}): ${error}`));
    };
    const cleanup = () => {
      child.off('message', onMessage);
      child.off('exit', onExit);
    };
    child.on('message', onMessage);
    child.on('exit', onExit);
  });
  return { child, message };
}

async function fixtureWorker(directory, behavior, firstOnly = true) {
  const workerPath = path.join(directory, `fixture-${behavior}-${firstOnly}.cjs`);
  const counterPath = path.join(directory, `counter-${behavior}-${firstOnly}`);
  await writeFile(workerPath, `
    const fs = require('node:fs');
    const counterPath = ${JSON.stringify(counterPath)};
    const mode = process.argv[2];
    if (mode !== '--storage-worker') process.exit(99);
    let count = 0;
    try { count = Number(fs.readFileSync(counterPath, 'utf8')); } catch {}
    fs.writeFileSync(counterPath, String(count + 1));
    const behavior = ${JSON.stringify(behavior)};
    const selected = ${JSON.stringify(firstOnly)} && count > 0 ? 'success' : behavior;
    const temp = \`.\${process.env.SIX_PROBE_GROUP_ID}.\${process.env.SIX_PROBE_TOKEN}.tmp\`;
    if (selected !== 'success') fs.writeFileSync(temp, 'fixture-temp', { flag: 'wx', mode: 0o600 });
    const protocol = (status) => JSON.stringify({ status, version: 1 }) + '\\n';
    if (selected === 'success') { process.stdout.write(protocol('created')); process.exit(0); }
    if (selected === 'timeout') { process.on('SIGTERM', () => {}); setInterval(() => {}, 1000); }
    else if (selected === 'stdout-overflow') { process.stdout.write('x'.repeat(300)); setInterval(() => {}, 1000); }
    else if (selected === 'stderr-overflow') { process.stderr.write('x'.repeat(5000)); setInterval(() => {}, 1000); }
    else if (selected === 'invalid-json') { process.stdout.write('{"bad":\\n'); process.exit(0); }
    else if (selected === 'duplicate-status') { process.stdout.write('{"status":"error","status":"created","version":1}\\n'); process.exit(0); }
    else if (selected === 'duplicate-version') { process.stdout.write('{"status":"created","version":2,"version":1}\\n'); process.exit(0); }
    else if (selected === 'leading-whitespace') { process.stdout.write(' {"status":"created","version":1}\\n'); process.exit(0); }
    else if (selected === 'trailing-whitespace') { process.stdout.write('{"status":"created","version":1} \\n'); process.exit(0); }
    else if (selected === 'bom') { process.stdout.write('\\ufeff{"status":"created","version":1}\\n'); process.exit(0); }
    else if (selected === 'extra-newline') { process.stdout.write('{"status":"created","version":1}\\n\\n'); process.exit(0); }
    else if (selected === 'concatenated-json') { process.stdout.write('{"status":"created","version":1}{"status":"created","version":1}\\n'); process.exit(0); }
    else if (selected === 'leading-log') { process.stdout.write('fixture log\\n{"status":"created","version":1}\\n'); process.exit(0); }
    else if (selected === 'noncanonical-key-order') { process.stdout.write('{"version":1,"status":"created"}\\n'); process.exit(0); }
    else if (selected === 'abnormal-exit') process.exit(42);
    else throw new Error('unknown fixture behavior');
  `);
  return workerPath;
}

async function fifoWorker(directory) {
  const workerPath = path.join(directory, 'fixture-fifo.cjs');
  const startedPath = path.join(directory, 'started');
  const readyPath = path.join(directory, 'ready');
  const releasePath = path.join(directory, 'release');
  await writeFile(workerPath, `
    const fs = require('node:fs');
    if (process.argv[2] !== '--storage-worker') process.exit(99);
    const id = process.env.SIX_PROBE_SESSION_ID;
    fs.appendFileSync(${JSON.stringify(startedPath)}, id + '\\n');
    const done = () => {
      process.stdout.write(JSON.stringify({ status: 'created', version: 1 }) + '\\n');
      process.exit(0);
    };
    if (id !== 'fifo-1') done();
    fs.writeFileSync(${JSON.stringify(readyPath)}, 'ready');
    const timer = setInterval(() => {
      if (fs.existsSync(${JSON.stringify(releasePath)})) {
        clearInterval(timer);
        done();
      }
    }, 5);
  `);
  return { readyPath, releasePath, startedPath, workerPath };
}

function instrumentedSpawn() {
  let active = 0;
  let cleanup = 0;
  let maximum = 0;
  const signals = [];
  const started = [];
  let storage = 0;
  return {
    spawnProcess(command, args, options) {
      if (args[1] === '--cleanup-worker') cleanup += 1;
      else {
        storage += 1;
        started.push(options.env.SIX_PROBE_SESSION_ID);
      }
      const child = spawn(command, args, options);
      const originalKill = child.kill.bind(child);
      child.kill = (signal) => {
        signals.push(signal);
        return originalKill(signal);
      };
      active += 1;
      maximum = Math.max(maximum, active);
      child.once('close', () => { active -= 1; });
      return child;
    },
    stats() { return { active, cleanup, maximum, signals: [...signals], started: [...started], storage }; },
  };
}

async function waitForFile(file, timeoutMs = 3_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try { await access(file); return; } catch {}
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  throw new Error(`file wait timed out: ${file}`);
}

test('127.0.0.1 외 주소 bind를 거부한다', async () => {
  const rootDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-bind-'));
  const receiver = createEvidenceReceiver({ expectedOrigin: ORIGIN, rootDir });
  await assert.rejects(receiver.listen({ host: '0.0.0.0', port: 0 }), /127\.0\.0\.1/);
});

test('정확한 extension origin과 JSON만 저장하고 CORS origin을 echo한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());

  const bytes = Buffer.from(JSON.stringify(evidence()));
  const response = await post(f.baseUrl, bytes);
  assert.equal(response.status, 201);
  assert.equal(response.headers['access-control-allow-origin'], ORIGIN);
  assert.equal(response.headers['access-control-allow-credentials'], undefined);
  assert.deepEqual(
    await readFile(path.join(f.rootDir, 'session-20260802', 'base-runtime.json')),
    bytes,
  );

  const preflight = await request(f.baseUrl, {
    headers: {
      'access-control-request-headers': 'content-type',
      'access-control-request-method': 'POST',
      origin: ORIGIN,
    },
    method: 'OPTIONS',
  });
  assert.equal(preflight.status, 204);
  assert.equal(preflight.headers['access-control-allow-origin'], ORIGIN);
  assert.equal(preflight.headers['access-control-allow-credentials'], undefined);
});

test('없거나 null 또는 웹 origin과 잘못된 content type을 거부한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());

  for (const origin of [undefined, 'null', 'https://example.com', `${ORIGIN}/`]) {
    const headers = { 'content-type': 'application/json' };
    if (origin !== undefined) headers.origin = origin;
    const response = await request(f.baseUrl, {
      body: JSON.stringify(evidence()),
      headers,
    });
    assert.equal(response.status, 403);
  }
  assert.equal((await request(f.baseUrl, {
    body: JSON.stringify(evidence()),
    headers: { 'content-type': 'text/plain', origin: ORIGIN },
  })).status, 415);
  assert.equal((await post(f.baseUrl, evidence({ extensionId: 'b'.repeat(32) }))).status, 400);
});

test('Content-Length와 chunked streaming의 1 MiB 초과를 모두 413으로 거부한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());

  const response = await post(f.baseUrl, Buffer.alloc(1_048_577, 0x20));
  assert.equal(response.status, 413);
  assert.equal(await chunkedOversizePost(f.baseUrl), 413);
  await assert.rejects(access(path.join(f.rootDir, 'session-20260802', 'base-runtime.json')));
});

test('raw Content-Length smaller와 larger spoof를 저장 없이 400으로 거부한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());
  const body = JSON.stringify(evidence());

  assert.equal(await rawHttp(f.baseUrl, body.length - 1, body), 400);
  assert.equal(await rawHttp(f.baseUrl, body.length + 10, body), 400);
  await assert.rejects(access(path.join(f.rootDir, 'session-20260802', 'base-runtime.json')));
});

test('JSON과 top-level metadata를 엄격히 검증한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());

  assert.equal((await post(f.baseUrl, '{')).status, 400);
  assert.equal((await post(f.baseUrl, { ...evidence(), unexpected: true })).status, 400);
  assert.equal((await post(f.baseUrl, evidence({ catalogSha256: 'A'.repeat(64) }))).status, 400);
  assert.equal((await post(f.baseUrl, evidence({ results: [] }))).status, 400);
  const duplicate = JSON.stringify(evidence()).replace(
    '"schemaVersion":1',
    '"schemaVersion":1,"schemaVersion":1',
  );
  assert.equal((await post(f.baseUrl, duplicate)).status, 400);
  const mismatched = evidence();
  mismatched.results[0].status = 'ABSENT';
  assert.equal((await post(f.baseUrl, mismatched)).status, 400);
});

test('Task4에서 생성되는 모든 terminal result shape를 수락한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());

  assert.equal((await post(f.baseUrl, allTerminalEvidence())).status, 201);
});

test('Kotlin과 Node 공통 reject corpus를 모두 거부한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());
  const cases = await loadRejectCorpus();
  assert.equal(cases.length, 10);

  for (const entry of cases) {
    const response = await post(f.baseUrl, applyCorpusOperation(entry.operation));
    assert.equal(response.status, 400, entry.id);
  }
});

test('잘못된 UTF-8 JSON bytes를 거부한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());
  const bytes = Buffer.from(JSON.stringify(evidence()));
  const marker = Buffer.from('"valueType":"object"');
  const markerIndex = bytes.indexOf(marker);
  assert.notEqual(markerIndex, -1);
  bytes[markerIndex + marker.length - 2] = 0xff;

  assert.equal((await post(f.baseUrl, bytes)).status, 400);
});

test('path traversal Unicode separator와 encoded separator ID를 거부한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());

  const values = [
    evidence({ sessionId: '../escape' }),
    evidence({ sessionId: 'session／escape' }),
    evidence({ groupId: 'base%2fruntime' }),
    evidence({ groupId: 'base\\runtime' }),
  ];
  for (const value of values) assert.equal((await post(f.baseUrl, value)).status, 400);
  assert.equal((await post(f.baseUrl, evidence(), { pathname: '/evidence/x%2fy' })).status, 404);
});

test('동일 bytes는 idempotent이고 다른 bytes는 overwrite하지 않는다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());

  const first = Buffer.from(JSON.stringify(evidence()));
  const different = Buffer.from(JSON.stringify(evidence({ browserVersionName: '30.0.2.62' })));
  assert.equal((await post(f.baseUrl, first)).status, 201);
  assert.equal((await post(f.baseUrl, first)).status, 200);
  assert.equal((await post(f.baseUrl, different)).status, 409);
  assert.deepEqual(
    await readFile(path.join(f.rootDir, 'session-20260802', 'base-runtime.json')),
    first,
  );
});

test('동시 저장도 exact bytes 한 개만 승리한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());
  const one = Buffer.from(JSON.stringify(evidence({ browserVersionName: '30.0.2.61' })));
  const two = Buffer.from(JSON.stringify(evidence({ browserVersionName: '30.0.2.62' })));

  const responses = await Promise.all([post(f.baseUrl, one), post(f.baseUrl, two)]);
  assert.deepEqual(responses.map((it) => it.status).sort(), [201, 409]);
  const stored = await readFile(path.join(f.rootDir, 'session-20260802', 'base-runtime.json'));
  assert.ok(stored.equals(one) || stored.equals(two));
});

test('temp publish 직전 외부 target 생성 race도 기존 bytes를 보존한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());
  const session = path.join(f.rootDir, 'session-20260802');
  await mkdir(session);
  const target = path.join(session, 'base-runtime.json');
  const sentinel = 'external-winner';
  const race = startRaceChild(`
    const fs = require('node:fs');
    const path = require('node:path');
    const [session, target, sentinel] = process.argv.slice(1);
    process.stdout.write('ready\\n');
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      const temp = fs.readdirSync(session).find((name) => name.endsWith('.tmp'));
      if (temp) {
        try { fs.writeFileSync(target, sentinel, { flag: 'wx' }); process.exit(0); }
        catch (error) { if (error.code !== 'EEXIST') throw error; }
      }
    }
    process.exit(2);
  `, [session, target, sentinel]);
  await race.ready;
  const body = Buffer.from(`${JSON.stringify(evidence())}${' '.repeat(1_040_000)}`);

  const response = await post(f.baseUrl, body);
  await race.completed;

  assert.equal(response.status, 409);
  assert.equal(await readFile(target, 'utf8'), sentinel);
});

test('session parent swap race에서 열린 directory 밖에 파일을 만들지 않는다', async (t) => {
  const originalCwd = process.cwd();
  const f = await fixture();
  t.after(() => f.receiver.close());
  const session = path.join(f.rootDir, 'session-20260802');
  const moved = path.join(f.rootDir, 'moved-session');
  const outside = await mkdtemp(path.join(os.tmpdir(), 'six-probe-race-outside-'));
  await mkdir(session);
  const race = startRaceChild(`
    const fs = require('node:fs');
    const [session, moved, outside] = process.argv.slice(1);
    process.stdout.write('ready\\n');
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      const temp = fs.readdirSync(session).find((name) => name.endsWith('.tmp'));
      if (temp) {
        fs.renameSync(session, moved);
        fs.symlinkSync(outside, session);
        process.exit(0);
      }
    }
    process.exit(2);
  `, [session, moved, outside]);
  await race.ready;
  const body = Buffer.from(`${JSON.stringify(evidence())}${' '.repeat(1_040_000)}`);

  const response = await post(f.baseUrl, body);
  await race.completed;

  assert.equal(response.status, 409);
  assert.deepEqual(await readdir(outside), []);
  assert.deepEqual(await readdir(moved), []);
  assert.equal(process.cwd(), originalCwd);
});

test('symlink parent와 target을 거부하고 target을 보존한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());
  const outside = await mkdtemp(path.join(os.tmpdir(), 'six-probe-outside-'));
  await symlink(outside, path.join(f.rootDir, 'session-20260802'));
  assert.equal((await post(f.baseUrl, evidence())).status, 409);

  const second = await fixture();
  t.after(() => second.receiver.close());
  const session = path.join(second.rootDir, 'session-20260802');
  await mkdir(session);
  const outsideFile = path.join(outside, 'outside.json');
  await symlink(outsideFile, path.join(session, 'base-runtime.json'));
  assert.equal((await post(second.baseUrl, evidence())).status, 409);
  await assert.rejects(readFile(outsideFile), { code: 'ENOENT' });
});

test('group writable directory와 hardlink target을 거부한다', async (t) => {
  const writable = await fixture();
  t.after(() => writable.receiver.close());
  await chmod(writable.rootDir, 0o770);
  assert.equal((await post(writable.baseUrl, evidence())).status, 409);

  const linked = await fixture();
  t.after(() => linked.receiver.close());
  const session = path.join(linked.rootDir, 'session-20260802');
  await mkdir(session);
  const source = path.join(linked.rootDir, 'source.json');
  const sentinel = Buffer.from('hardlink-winner');
  await writeFile(source, sentinel, { mode: 0o600 });
  await link(source, path.join(session, 'base-runtime.json'));

  assert.equal((await post(linked.baseUrl, evidence())).status, 409);
  assert.deepEqual(await readFile(source), sentinel);
});

test('부모 receiver는 시작 CWD가 교체되어도 chdir 없이 계속 처리한다', async (t) => {
  const cwd = await mkdtemp(path.join(os.tmpdir(), 'six-probe-parent-cwd-'));
  const movedCwd = `${cwd}-moved`;
  const rootDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-parent-root-'));
  const harness = startCwdHarness(cwd, rootDir);
  t.after(() => { if (!harness.child.killed) harness.child.kill('SIGKILL'); });
  const ready = await harness.message('ready');
  const baseUrl = `http://127.0.0.1:${ready.port}`;

  await rename(cwd, movedCwd);
  await mkdir(cwd);
  assert.equal((await post(baseUrl, evidence())).status, 201);
  assert.equal((await post(baseUrl, evidence())).status, 200);

  const reported = harness.message('report');
  harness.child.send('report');
  const report = await reported;
  assert.equal(report.chdirCalls, 0);
  assert.equal(report.identityStable, true);
  assert.deepEqual(await readdir(cwd), []);
  assert.deepEqual(await readdir(movedCwd), []);
  assert.deepEqual(
    await readFile(path.join(rootDir, 'session-20260802', 'base-runtime.json')),
    Buffer.from(JSON.stringify(evidence())),
  );
});

test('worker timeout·출력·protocol·exit·spawn 실패는 cleanup 후 slot을 반환한다', async (t) => {
  const productionWorker = fileURLToPath(new URL('./api-probe-receiver.mjs', import.meta.url));
  const behaviors = [
    'timeout',
    'stdout-overflow',
    'stderr-overflow',
    'invalid-json',
    'duplicate-status',
    'duplicate-version',
    'leading-whitespace',
    'trailing-whitespace',
    'bom',
    'extra-newline',
    'concatenated-json',
    'leading-log',
    'noncanonical-key-order',
    'abnormal-exit',
  ];
  for (const behavior of behaviors) {
    await t.test(behavior, async () => {
      const rootDir = await mkdtemp(path.join(os.tmpdir(), `six-probe-boundary-${behavior}-`));
      const fixtureDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-worker-fixture-'));
      const storageWorkerPath = await fixtureWorker(fixtureDir, behavior);
      const tracker = instrumentedSpawn();
      const receiver = evidenceReceiverTestHooks.create({ expectedOrigin: ORIGIN, rootDir }, {
        cleanupWorkerPath: productionWorker,
        pool: evidenceReceiverTestHooks.workerPool(1, 2),
        spawnProcess: tracker.spawnProcess,
        storageWorkerPath,
        workerTimeoutMs: 50,
      });
      await receiver.listen({ host: '127.0.0.1', port: 0 });
      const baseUrl = `http://127.0.0.1:${receiver.address().port}`;
      try {
        assert.equal((await post(baseUrl, evidence())).status, 409);
        assert.equal(tracker.stats().cleanup, 1);
        const sessionFiles = await readdir(path.join(rootDir, 'session-20260802'));
        assert.equal(sessionFiles.some((name) => name.endsWith('.tmp')), false);
        if (behavior === 'timeout') {
          assert.deepEqual(tracker.stats().signals, ['SIGTERM', 'SIGKILL']);
        }
        assert.equal((await post(baseUrl, evidence())).status, 201);
        assert.equal(tracker.stats().active, 0);
      } finally {
        await receiver.close();
      }
    });
  }

  await t.test('spawn-error', async () => {
    const rootDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-boundary-spawn-'));
    const fixtureDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-worker-fixture-'));
    const storageWorkerPath = await fixtureWorker(fixtureDir, 'success', false);
    const tracker = instrumentedSpawn();
    let failStorageSpawn = true;
    const receiver = evidenceReceiverTestHooks.create({ expectedOrigin: ORIGIN, rootDir }, {
      cleanupWorkerPath: productionWorker,
      pool: evidenceReceiverTestHooks.workerPool(1, 2),
      spawnProcess(command, args, options) {
        if (args[1] === '--storage-worker' && failStorageSpawn) {
          failStorageSpawn = false;
          const error = new Error('fixture spawn error');
          error.code = 'EACCES';
          throw error;
        }
        return tracker.spawnProcess(command, args, options);
      },
      storageWorkerPath,
      workerTimeoutMs: 50,
    });
    await receiver.listen({ host: '127.0.0.1', port: 0 });
    const baseUrl = `http://127.0.0.1:${receiver.address().port}`;
    try {
      assert.equal((await post(baseUrl, evidence())).status, 409);
      assert.equal(tracker.stats().cleanup, 1);
      assert.equal((await post(baseUrl, evidence())).status, 201);
      assert.equal(tracker.stats().active, 0);
    } finally {
      await receiver.close();
    }
  });
});

test('global worker pool은 FIFO 상한과 503 backpressure를 적용한다', async (t) => {
  const rootDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-pool-'));
  const fixtureDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-worker-fixture-'));
  const fifo = await fifoWorker(fixtureDir);
  const tracker = instrumentedSpawn();
  const receiver = evidenceReceiverTestHooks.create({ expectedOrigin: ORIGIN, rootDir }, {
    cleanupWorkerPath: fifo.workerPath,
    pool: evidenceReceiverTestHooks.workerPool(1, 2),
    spawnProcess: tracker.spawnProcess,
    storageWorkerPath: fifo.workerPath,
    workerTimeoutMs: 1_000,
  });
  await receiver.listen({ host: '127.0.0.1', port: 0 });
  t.after(() => receiver.close());
  const baseUrl = `http://127.0.0.1:${receiver.address().port}`;

  const taskIds = ['fifo-1', 'fifo-2', 'fifo-3', 'fifo-4'];
  const submitted = [];
  const submit = (sessionId) => {
    submitted.push(sessionId);
    return post(baseUrl, evidence({ sessionId }));
  };
  const requests = [submit(taskIds[0])];
  await waitForFile(fifo.readyPath);
  for (const sessionId of taskIds.slice(1)) {
    requests.push(submit(sessionId));
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  await writeFile(fifo.releasePath, 'release');

  const responses = await Promise.all(requests);
  assert.deepEqual(responses.map((value) => value.status), [201, 201, 201, 503]);
  assert.equal(responses[3].headers['retry-after'], '1');
  const childStarted = (await readFile(fifo.startedPath, 'utf8')).trimEnd().split('\n');
  assert.deepEqual(submitted, taskIds);
  assert.deepEqual(childStarted, submitted.slice(0, 3));
  assert.deepEqual(tracker.stats().started, submitted.slice(0, 3));
  assert.equal(tracker.stats().maximum, 1);
  assert.equal(tracker.stats().active, 0);
  assert.equal(tracker.stats().cleanup, 0);
});

test('원점 위조 flood는 worker를 만들지 않고 6개 group은 bounded pool에서 처리된다', async (t) => {
  const rootDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-pool-groups-'));
  const fixtureDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-worker-fixture-'));
  const storageWorkerPath = await fixtureWorker(fixtureDir, 'success', false);
  const tracker = instrumentedSpawn();
  const receiver = evidenceReceiverTestHooks.create({ expectedOrigin: ORIGIN, rootDir }, {
    cleanupWorkerPath: storageWorkerPath,
    pool: evidenceReceiverTestHooks.workerPool(2, 8),
    spawnProcess: tracker.spawnProcess,
    storageWorkerPath,
    workerTimeoutMs: 500,
  });
  await receiver.listen({ host: '127.0.0.1', port: 0 });
  t.after(() => receiver.close());
  const baseUrl = `http://127.0.0.1:${receiver.address().port}`;

  const forged = await Promise.all(Array.from({ length: 20 }, () => request(baseUrl, {
    body: JSON.stringify(evidence()),
    headers: { 'content-type': 'application/json', origin: 'https://attacker.invalid' },
  })));
  assert.ok(forged.every((value) => value.status === 403));
  assert.equal(tracker.stats().storage, 0);

  const groups = [
    'advanced-runtime', 'base-runtime', 'identity-privacy', 'network', 'tab-page', 'user-interface',
  ];
  const responses = await Promise.all(groups.map((groupId, index) => post(baseUrl, evidence({
    groupId,
    sessionId: `session-group-${index}`,
  }))));
  assert.ok(responses.every((value) => value.status === 201));
  assert.ok(tracker.stats().maximum <= 2);
  assert.equal(tracker.stats().storage, 6);
});

test('cleanup worker는 session path swap 후도 pinned inode temp만 삭제한다', async (t) => {
  const productionWorker = fileURLToPath(new URL('./api-probe-receiver.mjs', import.meta.url));
  const rootDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-cleanup-race-'));
  const fixtureDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-worker-fixture-'));
  const storageWorkerPath = await fixtureWorker(fixtureDir, 'abnormal-exit', false);
  const readyMarker = path.join(fixtureDir, 'cleanup-ready');
  const cleanupWrapper = path.join(fixtureDir, 'cleanup-wrapper.cjs');
  await writeFile(cleanupWrapper, `
    const fs = require('node:fs');
    const { spawnSync } = require('node:child_process');
    fs.writeFileSync(${JSON.stringify(readyMarker)}, 'ready');
    process.kill(process.pid, 'SIGSTOP');
    const result = spawnSync(process.execPath, [${JSON.stringify(productionWorker)}, '--cleanup-worker'], {
      env: process.env,
      stdio: 'inherit',
    });
    process.exit(result.status ?? 23);
  `);
  let cleanupChild;
  let cleanupEnvironment;
  const receiver = evidenceReceiverTestHooks.create({ expectedOrigin: ORIGIN, rootDir }, {
    cleanupWorkerPath: cleanupWrapper,
    pool: evidenceReceiverTestHooks.workerPool(1, 2),
    spawnProcess(command, args, options) {
      const child = spawn(command, args, options);
      if (args[1] === '--cleanup-worker') {
        cleanupChild = child;
        cleanupEnvironment = options.env;
      }
      return child;
    },
    storageWorkerPath,
    workerTimeoutMs: 1_000,
  });
  await receiver.listen({ host: '127.0.0.1', port: 0 });
  t.after(() => receiver.close());
  const baseUrl = `http://127.0.0.1:${receiver.address().port}`;
  const response = post(baseUrl, evidence());
  await waitForFile(readyMarker);

  const session = path.join(rootDir, 'session-20260802');
  const moved = path.join(rootDir, 'moved-session');
  const outside = await mkdtemp(path.join(os.tmpdir(), 'six-probe-cleanup-outside-'));
  await rename(session, moved);
  await symlink(outside, session);
  const tempName = `.base-runtime.${cleanupEnvironment.SIX_PROBE_TOKEN}.tmp`;
  const sentinel = Buffer.from('attacker-file');
  await writeFile(path.join(outside, tempName), sentinel, { mode: 0o600 });
  cleanupChild.kill('SIGCONT');

  assert.equal((await response).status, 409);
  assert.equal((await readdir(moved)).some((name) => name.endsWith('.tmp')), false);
  assert.deepEqual(await readFile(path.join(outside, tempName)), sentinel);
});

test('session receiver는 exact 6 origins을 group과 binding해 full evidence로 저장한다', async (t) => {
  const current = await sessionFixture();
  t.after(() => current.receiver.close());
  const config = sessionConfig();
  for (const binding of config.bindings) {
    const origin = `chrome-extension://${binding.extensionId}`;
    const response = await sessionPost(current.baseUrl, origin, submission(binding.groupId));
    assert.equal(response.status, 201);
    assert.equal(response.headers['access-control-allow-origin'], origin);
    const stored = JSON.parse(await readFile(
      path.join(current.rootDir, config.sessionId, `${binding.groupId}.json`),
      'utf8',
    ));
    assert.equal(stored.extensionId, binding.extensionId);
    assert.equal(stored.wrapperSignerSha256, binding.wrapperSignerSha256);
    assert.equal(stored.browserPackage, config.browserPackage);
    assert.equal(stored.groupId, binding.groupId);
  }
});

test('session receiver는 origin group catalog client provenance NOT_RUN malformed JSON을 거부한다', async (t) => {
  const current = await sessionFixture();
  t.after(() => current.receiver.close());
  const [first, second] = sessionConfig().bindings;
  const origin = `chrome-extension://${first.extensionId}`;
  assert.equal((await sessionPost(current.baseUrl, ORIGIN, submission(first.groupId))).status, 403);
  assert.equal((await sessionPost(current.baseUrl, origin, submission(second.groupId))).status, 400);
  assert.equal((await sessionPost(current.baseUrl, origin, submission(first.groupId, { catalogSha256: SHA_D }))).status, 400);
  assert.equal((await sessionPost(current.baseUrl, origin, {
    ...submission(first.groupId), extensionId: first.extensionId,
  })).status, 400);
  const notRun = submission(first.groupId);
  notRun.results[0].status = 'NOT_RUN';
  notRun.results[0].context = 'user_gesture';
  notRun.results[0].detail = { kind: 'gesture-required' };
  assert.equal((await sessionPost(current.baseUrl, origin, notRun)).status, 400);
  assert.equal((await sessionPost(current.baseUrl, origin, '{"schemaVersion":1,"schemaVersion":1}')).status, 400);
});

test('session receiver는 canonical 동일 submission은 200 비canonical은 400 다른 bytes는 409로 보존한다', async (t) => {
  const current = await sessionFixture();
  t.after(() => current.receiver.close());
  const binding = sessionConfig().bindings[0];
  const origin = `chrome-extension://${binding.extensionId}`;
  const value = submission(binding.groupId);
  assert.equal((await sessionPost(current.baseUrl, origin, value)).status, 201);
  const noncanonical = JSON.stringify({
    schemaVersion: value.schemaVersion,
    results: value.results,
    groupId: value.groupId,
    catalogSha256: value.catalogSha256,
  });
  assert.equal((await sessionPost(current.baseUrl, origin, noncanonical)).status, 400);
  assert.equal((await sessionPost(current.baseUrl, origin, value)).status, 200);
  const changed = structuredClone(value);
  changed.results[0].durationMs = 2;
  assert.equal((await sessionPost(current.baseUrl, origin, changed)).status, 409);
});

test('session receiver CLI는 0600 config와 origin mode의 배타적 선택을 강제한다', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'six-probe-session-cli-'));
  const configPath = path.join(root, 'session.json');
  const receiverPath = fileURLToPath(new URL('./api-probe-receiver.mjs', import.meta.url));
  const canonical = {
    schemaVersion: 1,
    sessionId: sessionConfig().sessionId,
    catalogSha256: sessionConfig().catalogSha256,
    browserPackage: sessionConfig().browserPackage,
    browserVersionName: sessionConfig().browserVersionName,
    browserVersionCode: sessionConfig().browserVersionCode,
    browserApkSetSha256: sessionConfig().browserApkSetSha256,
    browserSignerSha256: sessionConfig().browserSignerSha256,
    bindings: sessionConfig().bindings,
  };
  await writeFile(configPath, `${JSON.stringify(canonical)}\n`, { mode: 0o644 });
  const unsafe = spawnSync(process.execPath, [receiverPath, '--session-config', configPath]);
  assert.equal(unsafe.status, 1);
  assert.match(unsafe.stderr.toString(), /0600/);

  await chmod(configPath, 0o600);
  const ambiguous = spawnSync(process.execPath, [
    receiverPath, '--session-config', configPath, '--origin', ORIGIN,
  ]);
  assert.equal(ambiguous.status, 1);
  assert.match(ambiguous.stderr.toString(), /exactly one/);
});
