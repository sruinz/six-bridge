import { createServer } from "node:http";
import { createHash, randomBytes } from "node:crypto";
import { lstat, realpath, writeFile } from "node:fs/promises";

const successHtml = `<!doctype html>
<meta charset="utf-8">
<title>SIX Bridge AI 레시피 검증</title>
<h1>URL 전달 확인 완료</h1>
<p>이 창을 닫고 검증 터미널로 돌아가세요.</p>`;

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function closeServer(server, sockets) {
  return new Promise((resolve) => {
    server.close(() => resolve());
    for (const socket of sockets) {
      socket.destroy();
    }
  });
}

function writeEvidence({ evidencePath, expectedUrl, requestMatched, reason, receivedUrl }) {
  const evidence = {
    schemaVersion: 1,
    requestMatched,
    reason,
    method: "GET",
    route: "/<redacted>/ext_extract",
    expectedUrlSha256: sha256(expectedUrl),
    receivedUrlSha256: sha256(receivedUrl),
    completedAt: new Date().toISOString(),
  };

  return writeFile(evidencePath, `${JSON.stringify(evidence)}\n`, {
    encoding: "utf8",
    flag: "wx",
    mode: 0o600,
  });
}

export function validateReceiverRequest(input) {
  if (input.method !== "GET") {
    return { ok: false, status: 405, reason: "method" };
  }
  if (input.pathname !== `/${input.token}/ext_extract`) {
    return { ok: false, status: 404, reason: "route" };
  }
  if (input.actualUrl !== input.expectedUrl) {
    return { ok: false, status: 400, reason: "url" };
  }
  return { ok: true, status: 200, reason: "matched" };
}

export async function startReceiver({
  bindHost,
  advertiseHost,
  port,
  expectedUrl,
  timeoutMs,
  evidencePath,
}) {
  const token = randomBytes(24).toString("hex");
  let timeoutHandle;
  let finish;
  let settled = false;
  let resolveCompleted;
  const sockets = new Set();
  const completed = new Promise((resolve) => {
    resolveCompleted = resolve;
  });
  const claim = () => {
    if (settled) {
      return false;
    }
    settled = true;
    clearTimeout(timeoutHandle);
    return true;
  };
  const server = createServer((request, response) => {
    const requestUrl = new URL(request.url, "http://receiver.invalid");
    const queryEntries = [...requestUrl.searchParams];
    const actualUrl = queryEntries.length === 1 && queryEntries[0][0] === "url"
      ? queryEntries[0][1]
      : "";
    const validation = validateReceiverRequest({
      method: request.method,
      pathname: requestUrl.pathname,
      token,
      actualUrl,
      expectedUrl,
    });

    if (!validation.ok) {
      response.writeHead(validation.status, { "content-type": "text/plain; charset=utf-8" });
      response.end(validation.reason);
      return;
    }

    if (!claim()) {
      response.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
      response.end("route");
      return;
    }

    response.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    response.end(successHtml, () => {
      void finish({ requestMatched: true, reason: "matched", receivedUrl: actualUrl });
    });
  });
  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });

  const close = async () => {
    claim();
    await closeServer(server, sockets);
  };

  finish = async ({ requestMatched, reason, receivedUrl }) => {
    let result = { requestMatched, reason };
    try {
      await writeEvidence({ evidencePath, expectedUrl, requestMatched, reason, receivedUrl });
    } catch {
      result = { requestMatched: false, reason: "evidence" };
    }

    await closeServer(server, sockets);
    resolveCompleted(result);
  };

  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, bindHost, () => {
      server.off("error", reject);
      resolve();
    });
  });
  const address = server.address();
  timeoutHandle = setTimeout(() => {
    if (claim()) {
      void finish({ requestMatched: false, reason: "timeout", receivedUrl: "" });
    }
  }, timeoutMs);

  return {
    baseUrl: `http://${advertiseHost}:${address.port}/${token}`,
    port: address.port,
    completed,
    close,
  };
}

function parseArguments(argumentsList) {
  const values = {};
  for (let index = 0; index < argumentsList.length; index += 2) {
    const option = argumentsList[index];
    const value = argumentsList[index + 1];
    if (!option?.startsWith("--") || value === undefined) {
      throw new Error("usage: --advertise-host --expected-url --evidence [--port] [--timeout-ms]");
    }
    values[option] = value;
  }

  for (const requiredOption of ["--advertise-host", "--expected-url", "--evidence"]) {
    if (!values[requiredOption]) {
      throw new Error(`missing required option: ${requiredOption}`);
    }
  }

  const port = Number(values["--port"] ?? "0");
  const timeoutMs = Number(values["--timeout-ms"] ?? "600000");
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    throw new Error("--port must be an integer from 0 to 65535");
  }
  if (!Number.isInteger(timeoutMs) || timeoutMs <= 0) {
    throw new Error("--timeout-ms must be a positive integer");
  }

  return {
    advertiseHost: values["--advertise-host"],
    expectedUrl: values["--expected-url"],
    evidencePath: values["--evidence"],
    port,
    timeoutMs,
  };
}

async function cliEvidencePath(evidencePath) {
  const rootInfo = await lstat(".local/evidence");
  if (!rootInfo.isDirectory() || rootInfo.isSymbolicLink()) {
    throw new Error(".local/evidence must be a real directory");
  }
  const evidenceRoot = await realpath(".local/evidence");
  const absolutePath = evidencePath.startsWith("/")
    ? evidencePath
    : `${process.cwd()}/${evidencePath}`;
  const separator = absolutePath.lastIndexOf("/");
  const parentPath = absolutePath.slice(0, separator) || "/";
  const filename = absolutePath.slice(separator + 1);
  if (!filename || filename === "." || filename === ".." || await realpath(parentPath) !== evidenceRoot) {
    throw new Error("--evidence must be directly inside .local/evidence");
  }

  const resolvedEvidencePath = `${evidenceRoot}/${filename}`;
  try {
    if ((await lstat(resolvedEvidencePath)).isSymbolicLink()) {
      throw new Error("--evidence must not be a symbolic link");
    }
  } catch (error) {
    if (error.code !== "ENOENT") {
      throw error;
    }
  }
  return resolvedEvidencePath;
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const evidencePath = await cliEvidencePath(options.evidencePath);
  const receiver = await startReceiver({
    bindHost: "0.0.0.0",
    ...options,
    evidencePath,
  });
  console.log(`baseUrl=${receiver.baseUrl}`);
  const result = await receiver.completed;
  if (!result.requestMatched) {
    throw new Error(`receiver completed without a matched request: ${result.reason}`);
  }
}

if (process.argv[1] && new URL(`file://${process.argv[1]}`).href === import.meta.url) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
