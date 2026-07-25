import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import {
  lstat,
  mkdir,
  mkdtemp,
  open,
  readFile,
  rename,
  rm,
  symlink,
  writeFile,
} from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

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

const receiverScript = fileURLToPath(
  new URL("./video-nas-lan-receiver.mjs", import.meta.url),
);

async function createCliProject(t) {
  const project = await mkdtemp(path.join(os.tmpdir(), "video-nas-cli-"));
  await mkdir(path.join(project, ".local", "evidence"), { recursive: true });
  t.after(async () => {
    await rm(project, { recursive: true, force: true });
  });
  return project;
}

function runCli(argumentsList, {
  t,
  cwd,
  onStdoutLine,
  nodeArguments,
  deadlineMs = 5_000,
} = {}) {
  const child = spawn(
    process.execPath,
    nodeArguments ?? [receiverScript, ...argumentsList],
    {
      cwd,
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  let stdout = "";
  let stdoutRemainder = "";
  let stderr = "";
  let closed = false;
  let deadlineExceeded = false;
  let spawnError;
  let callbackError;
  let terminatePromise;
  let forceKillHandle;
  let resolveClosed;
  const closedPromise = new Promise((resolve) => {
    resolveClosed = resolve;
  });
  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdout.on("data", (chunk) => {
    stdout += chunk;
    stdoutRemainder += chunk;
    const lines = stdoutRemainder.split("\n");
    stdoutRemainder = lines.pop();
    for (const line of lines) {
      try {
        onStdoutLine?.(line);
      } catch (error) {
        callbackError = error;
        void terminate();
      }
    }
  });
  child.stderr.on("data", (chunk) => {
    stderr += chunk;
  });
  child.once("error", (error) => {
    spawnError = error;
  });
  child.once("close", (code, signal) => {
    closed = true;
    resolveClosed({
      code,
      signal,
      pid: child.pid,
      stdout,
      stderr,
      deadlineExceeded,
    });
  });

  const terminate = async () => {
    if (!terminatePromise) {
      terminatePromise = (async () => {
        if (!closed) {
          child.kill("SIGTERM");
          forceKillHandle = setTimeout(() => {
            if (!closed) {
              child.kill("SIGKILL");
            }
          }, 250);
        }
        await closedPromise;
      })();
    }
    try {
      await terminatePromise;
    } finally {
      clearTimeout(forceKillHandle);
    }
  };
  t.after(terminate);

  const deadlineHandle = setTimeout(() => {
    deadlineExceeded = true;
    void terminate();
  }, deadlineMs);
  return (async () => {
    try {
      const result = await closedPromise;
      if (spawnError) {
        throw spawnError;
      }
      if (callbackError) {
        throw callbackError;
      }
      return result;
    } finally {
      clearTimeout(deadlineHandle);
    }
  })();
}

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

function postCliDownload(configUrl, downloadUrl) {
  const config = new URL(configUrl);
  const body = JSON.stringify({
    url: downloadUrl,
    resolution: "best",
  });
  return request(`${config.origin}${config.pathname}/rest`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${config.hash.slice(1)}`,
      "content-type": "application/json",
      "content-length": Buffer.byteLength(body),
    },
    body,
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

test("runCli deadline은 종료하지 않는 child를 kill하고 close까지 기다린다", async (t) => {
  const result = await runCli([], {
    t,
    nodeArguments: ["-e", "setInterval(() => {}, 1000);"],
    deadlineMs: 25,
  });

  assert.equal(result.deadlineExceeded, true);
  assert.equal(result.code, null);
  assert.equal(result.signal, "SIGTERM");
  assert.throws(() => process.kill(result.pid, 0), { code: "ESRCH" });
});

test("runCli after cleanup은 완료되지 않은 child를 kill하고 close까지 기다린다", async (t) => {
  let childResultPromise;
  let markReady;
  const ready = new Promise((resolve) => {
    markReady = resolve;
  });

  await t.test("controlled child", async (childTest) => {
    childResultPromise = runCli([], {
      t: childTest,
      nodeArguments: [
        "-e",
        'process.stdout.write("ready\\n"); setInterval(() => {}, 1000);',
      ],
      deadlineMs: 5_000,
      onStdoutLine(line) {
        if (line === "ready") {
          markReady();
        }
      },
    });
    await settleWithin(ready);
  });

  const result = await settleWithin(childResultPromise);
  assert.equal(result.deadlineExceeded, false);
  assert.equal(result.code, null);
  assert.equal(result.signal, "SIGTERM");
  assert.throws(() => process.kill(result.pid, 0), { code: "ESRCH" });
});

test("CLI는 mode·host·mode별 URL과 정수 경계를 검증한다", async (t) => {
  const project = await createCliProject(t);
  const evidence = ".local/evidence/arguments.json";
  const rejectedCases = [
    [
      "mode 없음",
      ["--advertise-host", "127.0.0.1", "--expected-url", exactInput.expectedUrl,
        "--evidence", evidence],
      /missing required option: --mode/,
    ],
    [
      "지원하지 않는 mode",
      ["--mode", "invalid", "--advertise-host", "127.0.0.1",
        "--evidence", evidence],
      /--mode must be page or hls/,
    ],
    [
      "page expected URL 없음",
      ["--mode", "page", "--advertise-host", "127.0.0.1",
        "--evidence", evidence],
      /missing required option: --expected-url/,
    ],
    [
      "hls expected URL 지정",
      ["--mode", "hls", "--advertise-host", "127.0.0.1",
        "--expected-url", exactInput.expectedUrl, "--evidence", evidence],
      /--expected-url is not allowed in hls mode/,
    ],
    [
      "advertise host 없음",
      ["--mode", "hls", "--evidence", evidence],
      /missing required option: --advertise-host/,
    ],
    [
      "evidence 없음",
      ["--mode", "hls", "--advertise-host", "127.0.0.1"],
      /missing required option: --evidence/,
    ],
    [
      "port 음수",
      ["--mode", "hls", "--advertise-host", "127.0.0.1",
        "--evidence", evidence, "--port", "-1"],
      /--port must be an integer from 0 to 65535/,
    ],
    [
      "port 상한 초과",
      ["--mode", "hls", "--advertise-host", "127.0.0.1",
        "--evidence", evidence, "--port", "65536"],
      /--port must be an integer from 0 to 65535/,
    ],
    [
      "port 소수",
      ["--mode", "hls", "--advertise-host", "127.0.0.1",
        "--evidence", evidence, "--port", "1.5"],
      /--port must be an integer from 0 to 65535/,
    ],
    [
      "timeout 0",
      ["--mode", "hls", "--advertise-host", "127.0.0.1",
        "--evidence", evidence, "--timeout-ms", "0"],
      /--timeout-ms must be a positive integer/,
    ],
    [
      "timeout 소수",
      ["--mode", "hls", "--advertise-host", "127.0.0.1",
        "--evidence", evidence, "--timeout-ms", "1.5"],
      /--timeout-ms must be a positive integer/,
    ],
  ];

  for (const [name, argumentsList, errorPattern] of rejectedCases) {
    await t.test(name, async () => {
      const result = await runCli(argumentsList, { t, cwd: project });
      assert.equal(result.code, 1);
      assert.equal(result.stdout.length, 0);
      assert.match(result.stderr, errorPattern);
    });
  }
});

test("CLI는 unknown·duplicate 옵션을 evidence 선점 전에 거부한다", async (t) => {
  const project = await createCliProject(t);
  const rejectedCases = [
    {
      name: "unknown",
      argumentsList: [
        "--mode", "hls",
        "--advertise-host", "127.0.0.1",
        "--evidence", ".local/evidence/unknown.json",
        "--timeout-ms", "1",
        "--unknown", "value",
      ],
      evidencePath: path.join(project, ".local", "evidence", "unknown.json"),
      errorPattern: /unknown option: --unknown/,
    },
    {
      name: "duplicate",
      argumentsList: [
        "--mode", "hls",
        "--mode", "hls",
        "--advertise-host", "127.0.0.1",
        "--evidence", ".local/evidence/duplicate.json",
        "--timeout-ms", "1",
      ],
      evidencePath: path.join(project, ".local", "evidence", "duplicate.json"),
      errorPattern: /duplicate option: --mode/,
    },
  ];

  for (const {
    name,
    argumentsList,
    evidencePath,
    errorPattern,
  } of rejectedCases) {
    await t.test(name, async () => {
      const result = await runCli(argumentsList, { t, cwd: project });
      assert.equal(result.code, 1);
      assert.equal(result.stdout.length, 0);
      assert.match(result.stderr, errorPattern);
      await assert.rejects(readFile(evidencePath, "utf8"), { code: "ENOENT" });
    });
  }
});

test("CLI는 evidence 외부 parent와 symlink를 거부한다", async (t) => {
  const project = await createCliProject(t);
  const externalDirectory = await mkdtemp(
    path.join(os.tmpdir(), "video-nas-cli-external-"),
  );
  const externalEvidence = path.join(externalDirectory, "evidence.json");
  const symlinkEvidence = path.join(
    project,
    ".local",
    "evidence",
    "link.json",
  );
  await symlink(externalEvidence, symlinkEvidence);
  t.after(async () => {
    await rm(externalDirectory, { recursive: true, force: true });
  });

  const outside = await runCli([
    "--mode", "hls",
    "--advertise-host", "127.0.0.1",
    "--evidence", externalEvidence,
  ], { t, cwd: project });
  assert.equal(outside.code, 1);
  assert.equal(outside.stdout.length, 0);
  assert.match(outside.stderr, /directly inside \.local\/evidence/);

  const symlinkResult = await runCli([
    "--mode", "hls",
    "--advertise-host", "127.0.0.1",
    "--evidence", ".local/evidence/link.json",
  ], { t, cwd: project });
  assert.equal(symlinkResult.code, 1);
  assert.equal(symlinkResult.stdout.length, 0);
  assert.match(symlinkResult.stderr, /symbolic link/);
});

test("CLI는 기존 evidence 파일이면 listen 전에 실패한다", async (t) => {
  const project = await createCliProject(t);
  const evidencePath = path.join(
    project,
    ".local",
    "evidence",
    "occupied.json",
  );
  await writeFile(evidencePath, "keep", { encoding: "utf8", mode: 0o600 });

  const result = await runCli([
    "--mode", "hls",
    "--advertise-host", "127.0.0.1",
    "--evidence", ".local/evidence/occupied.json",
  ], { t, cwd: project });

  assert.equal(result.code, 1);
  assert.equal(result.stdout.length, 0);
  assert.match(result.stderr, /EEXIST/);
  assert.equal(await readFile(evidencePath, "utf8"), "keep");
});

test("CLI는 evidence root 교체 뒤에도 선점한 handle에만 timeout을 기록한다", {
  timeout: 4_000,
}, async (t) => {
  const project = await createCliProject(t);
  const evidenceRoot = path.join(project, ".local", "evidence");
  const movedRoot = path.join(project, ".local", "evidence-moved");
  const externalDirectory = await mkdtemp(
    path.join(os.tmpdir(), "video-nas-cli-external-"),
  );
  const filename = "root-race.json";
  let rootSwapped = false;
  let configSecrets;
  t.after(async () => {
    if (rootSwapped) {
      await rm(evidenceRoot, { recursive: true, force: true });
      await rename(movedRoot, evidenceRoot);
    }
    await rm(externalDirectory, { recursive: true, force: true });
  });

  let resolveSwapped;
  let rejectSwapped;
  const swapped = new Promise((resolve, reject) => {
    resolveSwapped = resolve;
    rejectSwapped = reject;
  });
  let swapStarted = false;
  const resultPromise = runCli([
    "--mode", "hls",
    "--advertise-host", "127.0.0.1",
    "--evidence", `.local/evidence/${filename}`,
    "--timeout-ms", "1000",
  ], {
    t,
    cwd: project,
    onStdoutLine(line) {
      if (!line.startsWith("configUrl=")) {
        return;
      }
      let config;
      try {
        config = new URL(line.slice("configUrl=".length));
      } catch {
        rejectSwapped(new Error("CLI emitted an invalid configUrl"));
        return;
      }
      configSecrets = [config.pathname.slice(1), config.hash.slice(1)];
      if (swapStarted) {
        return;
      }
      swapStarted = true;
      void (async () => {
        await rename(evidenceRoot, movedRoot);
        await symlink(externalDirectory, evidenceRoot);
        rootSwapped = true;
        resolveSwapped();
      })().catch(rejectSwapped);
    },
  });

  await swapped;
  const result = await resultPromise;
  assert.equal(result.code, 1);
  assert.match(result.stderr, /completed without a matched request: timeout/);
  await assert.rejects(
    readFile(path.join(externalDirectory, filename), "utf8"),
    { code: "ENOENT" },
  );

  await rm(evidenceRoot, { recursive: true, force: true });
  await rename(movedRoot, evidenceRoot);
  rootSwapped = false;
  const evidencePath = path.join(evidenceRoot, filename);
  const evidenceText = await readFile(evidencePath, "utf8");
  const evidence = JSON.parse(evidenceText);
  assert.equal(evidence.requestMatched, false);
  assert.equal(evidence.reason, "timeout");
  assert.equal((await lstat(evidencePath)).mode & 0o777, 0o600);
  assert.equal(evidenceText.includes("http://"), false);
  for (const secret of configSecrets) {
    assert.equal(evidenceText.includes(secret), false);
  }
});

test("CLI listen 실패는 server와 선점한 evidence handle을 정리한다", async (t) => {
  const project = await createCliProject(t);
  const holder = http.createServer((_request, response) => response.end("held"));
  let holderListening = true;
  await new Promise((resolve, reject) => {
    holder.once("error", reject);
    holder.listen(0, "0.0.0.0", resolve);
  });
  const occupiedPort = holder.address().port;
  t.after(async () => {
    if (holderListening) {
      await new Promise((resolve) => holder.close(resolve));
    }
  });

  const result = await runCli([
    "--mode", "hls",
    "--advertise-host", "127.0.0.1",
    "--evidence", ".local/evidence/listen-failure.json",
    "--port", String(occupiedPort),
  ], { t, cwd: project });

  assert.equal(result.code, 1);
  assert.equal(result.stdout.length, 0);
  assert.match(result.stderr, /EADDRINUSE/);
  const evidencePath = path.join(
    project,
    ".local",
    "evidence",
    "listen-failure.json",
  );
  assert.equal(await readFile(evidencePath, "utf8"), "");
  assert.equal((await lstat(evidencePath)).mode & 0o777, 0o600);
  await writeFile(evidencePath, "released", { encoding: "utf8", flag: "r+" });

  await new Promise((resolve) => holder.close(resolve));
  holderListening = false;
  const replacement = http.createServer();
  await new Promise((resolve, reject) => {
    replacement.once("error", reject);
    replacement.listen(occupiedPort, "0.0.0.0", resolve);
  });
  await new Promise((resolve) => replacement.close(resolve));
});

test("CLI page subprocess는 configUrl의 정확한 POST로 성공하고 정리한다", {
  timeout: 4_000,
}, async (t) => {
  const project = await createCliProject(t);
  const evidencePath = path.join(
    project,
    ".local",
    "evidence",
    "page-success.json",
  );
  const stdoutLabels = [];
  let exchangeStarted = false;
  let resolveExchange;
  const exchange = new Promise((resolve) => {
    resolveExchange = resolve;
  });
  const resultPromise = runCli([
    "--mode", "page",
    "--advertise-host", "127.0.0.1",
    "--expected-url", exactInput.expectedUrl,
    "--evidence", ".local/evidence/page-success.json",
    "--timeout-ms", "1000",
  ], {
    t,
    cwd: project,
    onStdoutLine(line) {
      if (!line.startsWith("configUrl=")) {
        stdoutLabels.push("unexpected");
        return;
      }
      stdoutLabels.push("configUrl");
      if (exchangeStarted) {
        return;
      }
      exchangeStarted = true;
      void (async () => {
        const configUrl = line.slice("configUrl=".length);
        const config = new URL(configUrl);
        const response = await postCliDownload(
          configUrl,
          exactInput.expectedUrl,
        );
        resolveExchange({
          ok: true,
          responseStatus: response.status,
          restUrl: `${config.origin}${config.pathname}/rest`,
          secrets: [config.pathname.slice(1), config.hash.slice(1)],
        });
      })().catch(() => {
        resolveExchange({ ok: false });
      });
    },
  });

  const [result, exchangeResult] = await Promise.all([
    resultPromise,
    settleWithin(exchange, 2_000),
  ]);
  assert.equal(exchangeResult.ok, true);
  assert.equal(exchangeResult.responseStatus, 200);
  assert.equal(result.code, 0);
  assert.equal(result.stderr.length, 0);
  assert.deepEqual(stdoutLabels, ["configUrl"]);

  const evidenceText = await readFile(evidencePath, "utf8");
  const evidence = JSON.parse(evidenceText);
  assert.equal(evidence.requestMatched, true);
  assert.equal(evidence.reason, "matched");
  assert.equal(evidence.expectedUrlSha256, sha256(exactInput.expectedUrl));
  assert.equal(evidence.receivedUrlSha256, evidence.expectedUrlSha256);
  assert.equal((await lstat(evidencePath)).mode & 0o777, 0o600);
  assert.equal(evidenceText.includes(exactInput.expectedUrl), false);
  for (const secret of exchangeResult.secrets) {
    assert.equal(evidenceText.includes(secret), false);
  }
  await assert.rejects(request(exchangeResult.restUrl));
  await writeFile(evidencePath, "released", { encoding: "utf8", flag: "r+" });
});

test("CLI HLS subprocess는 probe GET과 정확한 HLS POST로 성공하고 정리한다", {
  timeout: 4_000,
}, async (t) => {
  const project = await createCliProject(t);
  const evidencePath = path.join(
    project,
    ".local",
    "evidence",
    "hls-success.json",
  );
  const stdoutLabels = [];
  let configUrl;
  let probeUrl;
  let exchangeStarted = false;
  let resolveExchange;
  const exchange = new Promise((resolve) => {
    resolveExchange = resolve;
  });
  const maybeExchange = () => {
    if (!configUrl || !probeUrl || exchangeStarted) {
      return;
    }
    exchangeStarted = true;
    void (async () => {
      const config = new URL(configUrl);
      const probe = new URL(probeUrl);
      const probeMatchesConfig = (
        probe.origin === config.origin &&
        probe.pathname === `${config.pathname}/probe.html` &&
        probe.search === "" &&
        probe.hash === ""
      );
      const probeResponse = await request(probeUrl);
      const masterUrl = `${config.origin}${config.pathname}/hls/master.m3u8`;
      const downloadResponse = await postCliDownload(configUrl, masterUrl);
      resolveExchange({
        ok: true,
        probeMatchesConfig,
        probeStatus: probeResponse.status,
        downloadStatus: downloadResponse.status,
        restUrl: `${config.origin}${config.pathname}/rest`,
        secrets: [config.pathname.slice(1), config.hash.slice(1)],
      });
    })().catch(() => {
      resolveExchange({ ok: false });
    });
  };
  const resultPromise = runCli([
    "--mode", "hls",
    "--advertise-host", "127.0.0.1",
    "--evidence", ".local/evidence/hls-success.json",
    "--timeout-ms", "1000",
  ], {
    t,
    cwd: project,
    onStdoutLine(line) {
      if (line.startsWith("configUrl=")) {
        stdoutLabels.push("configUrl");
        configUrl = line.slice("configUrl=".length);
      } else if (line.startsWith("probeUrl=")) {
        stdoutLabels.push("probeUrl");
        probeUrl = line.slice("probeUrl=".length);
      } else {
        stdoutLabels.push("unexpected");
      }
      maybeExchange();
    },
  });

  const [result, exchangeResult] = await Promise.all([
    resultPromise,
    settleWithin(exchange, 2_000),
  ]);
  assert.equal(exchangeResult.ok, true);
  assert.equal(exchangeResult.probeMatchesConfig, true);
  assert.equal(exchangeResult.probeStatus, 200);
  assert.equal(exchangeResult.downloadStatus, 200);
  assert.equal(result.code, 0);
  assert.equal(result.stderr.length, 0);
  assert.deepEqual(stdoutLabels, ["configUrl", "probeUrl"]);

  const evidenceText = await readFile(evidencePath, "utf8");
  const evidence = JSON.parse(evidenceText);
  assert.equal(evidence.requestMatched, true);
  assert.equal(evidence.reason, "matched");
  assert.equal(evidence.expectedUrlSha256, evidence.receivedUrlSha256);
  assert.equal((await lstat(evidencePath)).mode & 0o777, 0o600);
  assert.equal(evidenceText.includes("http://"), false);
  for (const secret of exchangeResult.secrets) {
    assert.equal(evidenceText.includes(secret), false);
  }
  await assert.rejects(request(exchangeResult.restUrl));
  await writeFile(evidencePath, "released", { encoding: "utf8", flag: "r+" });
});

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
  assert.equal(/m3u8/i.test(evidenceText), false);
  assert.equal(evidenceText.includes(details.runToken), false);
  assert.equal(evidenceText.includes(details.bearerToken), false);
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
  assert.equal(/^[0-9a-f]{48}$/.test(details.runToken), true);
  assert.equal(/^[0-9a-f]{48}$/.test(details.bearerToken), true);
  assert.equal(details.runToken !== details.bearerToken, true);

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
  assert.equal(/youtube\.com|authorization|configUrl/i.test(evidenceText), false);
  assert.equal(evidenceText.includes(details.runToken), false);
  assert.equal(evidenceText.includes(details.bearerToken), false);
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
  assert.equal(/youtube\.com|authorization|configUrl/i.test(evidenceText), false);
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
