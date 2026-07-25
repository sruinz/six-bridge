import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, open, readFile, rm } from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  startVideoNasReceiver,
  validateDownloadRequest,
} from "./video-nas-lan-receiver.mjs";

const exactInput = {
  method: "POST",
  pathname: "/run/rest",
  search: "",
  authorization: "Bearer secret",
  contentType: "application/json;charset=UTF-8",
  bodyText: JSON.stringify({
    url: "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
    resolution: "best",
  }),
  runToken: "run",
  bearerToken: "secret",
  expectedUrl: "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
};

function request(url, options = {}) {
  return new Promise((resolve, reject) => {
    const requestHandle = http.request(url, options, (response) => {
      let body = "";
      response.setEncoding("utf8");
      response.on("data", (chunk) => {
        body += chunk;
      });
      response.on("end", () => {
        resolve({
          status: response.statusCode,
          contentType: response.headers["content-type"] ?? "",
          body,
        });
      });
    });
    requestHandle.on("error", reject);
    requestHandle.end(options.body);
  });
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

async function settleWithin(promise, timeoutMs = 500) {
  let timeoutHandle;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timeoutHandle = setTimeout(() => {
          reject(new Error(`promise did not settle within ${timeoutMs}ms`));
        }, timeoutMs);
      }),
    ]);
  } finally {
    clearTimeout(timeoutHandle);
  }
}

function receiverDetails(receiver) {
  const config = new URL(receiver.configUrl);
  return {
    runToken: config.pathname.slice(1),
    bearerToken: config.hash.slice(1),
    restUrl: `${config.origin}${config.pathname}/rest`,
    masterUrl: `${config.origin}${config.pathname}/hls/master.m3u8`,
  };
}

function postJson(receiver, body, overrides = {}) {
  const details = receiverDetails(receiver);
  const bodyText = typeof body === "string" ? body : JSON.stringify(body);
  return request(overrides.url ?? details.restUrl, {
    method: "POST",
    headers: {
      authorization: overrides.authorization ?? `Bearer ${details.bearerToken}`,
      "content-type": overrides.contentType ?? "application/json",
      "content-length": Buffer.byteLength(bodyText),
    },
    body: bodyText,
  });
}

async function startTestReceiver(t, options) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "video-nas-receiver-"));
  const evidencePath = path.join(directory, "evidence.json");
  const receiver = await startVideoNasReceiver({
    bindHost: "127.0.0.1",
    advertiseHost: "127.0.0.1",
    port: 0,
    expectedUrl: exactInput.expectedUrl,
    timeoutMs: 1_000,
    evidencePath,
    ...options,
  });
  receiver.testEvidencePath = evidencePath;
  t.after(async () => {
    await receiver.close();
    await rm(directory, { recursive: true, force: true });
  });
  return receiver;
}

test("정확한 Bearer POST만 허용한다", () => {
  assert.deepEqual(validateDownloadRequest(exactInput), {
    ok: true,
    status: 200,
    reason: "matched",
    url: exactInput.expectedUrl,
    resolution: "best",
  });
});

test("POST 계약을 벗어난 요청을 거부한다", async (t) => {
  const rejectedCases = [
    ["method", { method: "GET" }, 405, "method"],
    ["route", { pathname: "/other/rest" }, 404, "route"],
    ["query", { search: "?extra=1" }, 400, "query"],
    ["authorization", { authorization: "Bearer other" }, 401, "authorization"],
    ["content-type", { contentType: "text/plain" }, 415, "content-type"],
    ["json", { bodyText: "{" }, 400, "json"],
    ["body-keys", { bodyText: '{"url":"x"}' }, 400, "body-keys"],
    [
      "url",
      {
        bodyText: JSON.stringify({
          url: "https://m.youtube.com/watch?v=other",
          resolution: "best",
        }),
      },
      400,
      "url",
    ],
    [
      "resolution",
      {
        bodyText: JSON.stringify({
          url: exactInput.expectedUrl,
          resolution: "720p",
        }),
      },
      400,
      "resolution",
    ],
  ];

  for (const [name, mutation, status, reason] of rejectedCases) {
    await t.test(name, () => {
      assert.deepEqual(validateDownloadRequest({
        ...exactInput,
        ...mutation,
      }), {
        ok: false,
        status,
        reason,
      });
    });
  }
});

test("HLS mode는 probe와 master playlist를 제공한다", async (t) => {
  const receiver = await startTestReceiver(t, { mode: "hls" });

  const probe = await request(receiver.probeUrl);
  assert.equal(probe.status, 200);
  assert.match(probe.body, /fetch\(.+master\.m3u8/);

  const playlist = await request(
    new URL("hls/master.m3u8", receiver.probeUrl).href,
  );
  assert.equal(playlist.status, 200);
  assert.match(playlist.contentType, /mpegurl/);
  assert.match(playlist.body, /^#EXTM3U/m);

  const wrongSource = await postJson(receiver, {
    url: exactInput.expectedUrl,
    resolution: "best",
  });
  const download = await postJson(receiver, {
    url: receiverDetails(receiver).masterUrl,
    resolution: "best",
  });
  assert.equal(wrongSource.status, 400);
  assert.equal(download.status, 200);
  assert.deepEqual(await receiver.completed, {
    requestMatched: true,
    reason: "matched",
  });
  const evidenceText = await readFile(receiver.testEvidencePath, "utf8");
  const details = receiverDetails(receiver);
  assert.doesNotMatch(evidenceText, /m3u8/i);
  assert.doesNotMatch(evidenceText, new RegExp(
    `${details.runToken}|${details.bearerToken}`,
  ));
});

test("page mode는 HLS GET endpoint를 제공하지 않는다", async (t) => {
  const receiver = await startTestReceiver(t, { mode: "page" });
  const config = new URL(receiver.configUrl);
  const tokenRoot = config.pathname.endsWith("/")
    ? config.pathname
    : `${config.pathname}/`;

  const probe = await request(
    new URL(`${tokenRoot}probe.html`, config.origin).href,
  );
  const playlist = await request(
    new URL(`${tokenRoot}hls/master.m3u8`, config.origin).href,
  );

  assert.equal(probe.status, 404);
  assert.equal(playlist.status, 404);
});

test("page mode의 첫 정확한 POST를 완료하고 모든 handle을 닫는다", async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "video-nas-receiver-"));
  const evidencePath = path.join(directory, "evidence.json");
  const evidenceFile = await open(evidencePath, "wx", 0o600);
  const receiver = await startVideoNasReceiver({
    bindHost: "127.0.0.1",
    advertiseHost: "127.0.0.1",
    port: 0,
    mode: "page",
    expectedUrl: exactInput.expectedUrl,
    timeoutMs: 1_000,
    evidencePath,
    evidenceFile,
  });
  t.after(async () => {
    await receiver.close();
    await rm(directory, { recursive: true, force: true });
  });

  const details = receiverDetails(receiver);
  assert.match(details.runToken, /^[0-9a-f]{48}$/);
  assert.match(details.bearerToken, /^[0-9a-f]{48}$/);
  assert.notEqual(details.runToken, details.bearerToken);

  const response = await postJson(receiver, {
    url: exactInput.expectedUrl,
    resolution: "best",
  });
  assert.equal(response.status, 200);
  assert.deepEqual(await receiver.completed, {
    requestMatched: true,
    reason: "matched",
  });
  const evidenceText = await readFile(evidencePath, "utf8");
  const evidence = JSON.parse(evidenceText);
  assert.deepEqual(Object.keys(evidence).sort(), [
    "authMatched",
    "completedAt",
    "expectedUrlSha256",
    "method",
    "reason",
    "receivedUrlSha256",
    "requestMatched",
    "resolution",
    "route",
    "schemaVersion",
  ]);
  assert.equal(evidence.schemaVersion, 1);
  assert.equal(evidence.requestMatched, true);
  assert.equal(evidence.reason, "matched");
  assert.equal(evidence.method, "POST");
  assert.equal(evidence.route, "/<redacted>/rest");
  assert.equal(evidence.authMatched, true);
  assert.equal(evidence.expectedUrlSha256, sha256(exactInput.expectedUrl));
  assert.equal(evidence.receivedUrlSha256, evidence.expectedUrlSha256);
  assert.equal(evidence.resolution, "best");
  assert.doesNotMatch(evidenceText, /youtube\.com|authorization|configUrl/i);
  assert.doesNotMatch(evidenceText, new RegExp(
    `${details.runToken}|${details.bearerToken}`,
  ));
  await assert.rejects(evidenceFile.writeFile("not-written"), { code: "EBADF" });
  await assert.rejects(request(details.restUrl));
});

test("동시의 정확한 POST 중 한 건만 원자적으로 성공한다", async (t) => {
  const receiver = await startTestReceiver(t, { mode: "page" });
  const body = {
    url: exactInput.expectedUrl,
    resolution: "best",
  };

  const responses = await Promise.all([
    postJson(receiver, body),
    postJson(receiver, body),
  ]);

  assert.deepEqual(responses.map(({ status }) => status).sort(), [200, 404]);
  assert.deepEqual(await receiver.completed, {
    requestMatched: true,
    reason: "matched",
  });
});

test("잘못된 route·token·authorization·URL은 success claim을 소비하지 않는다", async (t) => {
  const receiver = await startTestReceiver(t, { mode: "page" });
  const details = receiverDetails(receiver);
  const config = new URL(receiver.configUrl);
  const exactBody = {
    url: exactInput.expectedUrl,
    resolution: "best",
  };

  const wrongToken = await postJson(receiver, exactBody, {
    url: `${config.origin}/other/rest`,
  });
  const wrongRoute = await postJson(receiver, exactBody, {
    url: `${config.origin}${config.pathname}/other`,
  });
  const wrongAuthorization = await postJson(receiver, exactBody, {
    authorization: "Bearer other",
  });
  const wrongUrl = await postJson(receiver, {
    url: "https://m.youtube.com/watch?v=other",
    resolution: "best",
  });
  const exact = await postJson(receiver, exactBody);

  assert.deepEqual([
    wrongToken.status,
    wrongRoute.status,
    wrongAuthorization.status,
    wrongUrl.status,
    exact.status,
  ], [404, 404, 401, 400, 200]);
  assert.deepEqual(await receiver.completed, {
    requestMatched: true,
    reason: "matched",
  });
  await assert.rejects(request(details.restUrl));
});

test("정확히 65,536 bytes인 유효 JSON body를 허용한다", async (t) => {
  const receiver = await startTestReceiver(t, { mode: "page" });
  const body = JSON.stringify({
    url: exactInput.expectedUrl,
    resolution: "best",
  });
  const boundaryBody = `${body}${" ".repeat(65_536 - Buffer.byteLength(body))}`;

  assert.equal(Buffer.byteLength(boundaryBody), 65_536);
  const response = await postJson(receiver, boundaryBody);
  assert.equal(response.status, 200);
  assert.deepEqual(await receiver.completed, {
    requestMatched: true,
    reason: "matched",
  });
});

test("65,537 bytes body는 413이고 success claim을 소비하지 않는다", async (t) => {
  const receiver = await startTestReceiver(t, { mode: "page" });

  const oversized = await postJson(receiver, "x".repeat(65_537));
  const exact = await postJson(receiver, {
    url: exactInput.expectedUrl,
    resolution: "best",
  });

  assert.equal(oversized.status, 413);
  assert.equal(oversized.body, "body-size");
  assert.equal(exact.status, 200);
  assert.deepEqual(await receiver.completed, {
    requestMatched: true,
    reason: "matched",
  });
});

test("요청 없는 close는 completed와 closed evidence를 settle하고 handle을 닫는다", {
  timeout: 1_000,
}, async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "video-nas-receiver-"));
  const evidencePath = path.join(directory, "evidence.json");
  const evidenceFile = await open(evidencePath, "wx", 0o600);
  const receiver = await startVideoNasReceiver({
    bindHost: "127.0.0.1",
    advertiseHost: "127.0.0.1",
    port: 0,
    mode: "page",
    expectedUrl: exactInput.expectedUrl,
    timeoutMs: 1_000,
    evidencePath,
    evidenceFile,
  });
  t.after(async () => {
    await receiver.close();
    await rm(directory, { recursive: true, force: true });
  });
  const details = receiverDetails(receiver);

  await receiver.close();

  assert.deepEqual(await settleWithin(receiver.completed), {
    requestMatched: false,
    reason: "closed",
  });
  const evidence = JSON.parse(await readFile(evidencePath, "utf8"));
  assert.equal(evidence.requestMatched, false);
  assert.equal(evidence.reason, "closed");
  await assert.rejects(evidenceFile.writeFile("not-written"), { code: "EBADF" });
  await assert.rejects(request(details.restUrl));
});

test("exact HTTP claim 직후 close해도 성공 finalization을 보존한다", {
  timeout: 1_500,
}, async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "video-nas-receiver-"));
  const evidencePath = path.join(directory, "evidence.json");
  const evidenceFile = await open(evidencePath, "wx", 0o600);
  const writeFile = evidenceFile.writeFile.bind(evidenceFile);
  let markWriteStarted;
  const writeStarted = new Promise((resolve) => {
    markWriteStarted = resolve;
  });
  let releaseWrite;
  const writeReleased = new Promise((resolve) => {
    releaseWrite = resolve;
  });
  evidenceFile.writeFile = async (...argumentsList) => {
    markWriteStarted();
    await writeReleased;
    return writeFile(...argumentsList);
  };
  const receiver = await startVideoNasReceiver({
    bindHost: "127.0.0.1",
    advertiseHost: "127.0.0.1",
    port: 0,
    mode: "page",
    expectedUrl: exactInput.expectedUrl,
    timeoutMs: 1_000,
    evidencePath,
    evidenceFile,
  });
  t.after(async () => {
    releaseWrite();
    await receiver.close();
    await rm(directory, { recursive: true, force: true });
  });
  const details = receiverDetails(receiver);
  const responsePromise = postJson(receiver, {
    url: exactInput.expectedUrl,
    resolution: "best",
  });

  await settleWithin(writeStarted);
  const closePromise = receiver.close();
  await new Promise((resolve) => setTimeout(resolve, 25));
  releaseWrite();

  const response = await settleWithin(responsePromise);
  assert.equal(response.status, 200);
  assert.deepEqual(await settleWithin(receiver.completed), {
    requestMatched: true,
    reason: "matched",
  });
  await settleWithin(closePromise);
  const evidence = JSON.parse(await readFile(evidencePath, "utf8"));
  assert.equal(evidence.requestMatched, true);
  assert.equal(evidence.reason, "matched");
  await assert.rejects(evidenceFile.writeFile("not-written"), { code: "EBADF" });
  await assert.rejects(request(details.restUrl));
});

test("timeout은 redacted evidence를 기록하고 server·socket·file handle을 닫는다", {
  timeout: 1_000,
}, async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "video-nas-receiver-"));
  const evidencePath = path.join(directory, "evidence.json");
  const evidenceFile = await open(evidencePath, "wx", 0o600);
  const receiver = await startVideoNasReceiver({
    bindHost: "127.0.0.1",
    advertiseHost: "127.0.0.1",
    port: 0,
    mode: "page",
    expectedUrl: exactInput.expectedUrl,
    timeoutMs: 25,
    evidencePath,
    evidenceFile,
  });
  t.after(async () => {
    await receiver.close();
    await rm(directory, { recursive: true, force: true });
  });

  assert.deepEqual(await receiver.completed, {
    requestMatched: false,
    reason: "timeout",
  });
  const evidenceText = await readFile(evidencePath, "utf8");
  const evidence = JSON.parse(evidenceText);
  assert.equal(evidence.requestMatched, false);
  assert.equal(evidence.reason, "timeout");
  assert.equal(evidence.route, "/<redacted>/rest");
  assert.equal(evidence.authMatched, false);
  assert.equal(evidence.expectedUrlSha256, sha256(exactInput.expectedUrl));
  assert.equal(evidence.receivedUrlSha256, sha256(""));
  assert.equal(evidence.resolution, "");
  assert.doesNotMatch(evidenceText, /youtube\.com|authorization|configUrl/i);
  await assert.rejects(evidenceFile.writeFile("not-written"), { code: "EBADF" });
  await assert.rejects(request(receiverDetails(receiver).restUrl));
});

test("점유 port의 listen 실패는 socket과 evidence FileHandle을 정리한다", async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "video-nas-receiver-"));
  const evidencePath = path.join(directory, "evidence.json");
  const evidenceFile = await open(evidencePath, "wx", 0o600);
  const holder = http.createServer((_request, response) => response.end("held"));
  let holderListening = true;
  await new Promise((resolve, reject) => {
    holder.once("error", reject);
    holder.listen(0, "127.0.0.1", resolve);
  });
  const occupiedPort = holder.address().port;
  t.after(async () => {
    if (holderListening) {
      await new Promise((resolve) => holder.close(resolve));
    }
    await rm(directory, { recursive: true, force: true });
  });

  await assert.rejects(startVideoNasReceiver({
    bindHost: "127.0.0.1",
    advertiseHost: "127.0.0.1",
    port: occupiedPort,
    mode: "page",
    expectedUrl: exactInput.expectedUrl,
    timeoutMs: 1_000,
    evidencePath,
    evidenceFile,
  }), (error) => {
    assert.equal(error.code, "EADDRINUSE");
    return true;
  });
  await assert.rejects(evidenceFile.writeFile("not-written"), { code: "EBADF" });

  await new Promise((resolve) => holder.close(resolve));
  holderListening = false;
  const replacement = http.createServer();
  await new Promise((resolve, reject) => {
    replacement.once("error", reject);
    replacement.listen(occupiedPort, "127.0.0.1", resolve);
  });
  await new Promise((resolve) => replacement.close(resolve));
});
