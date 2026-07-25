import { createHash, randomBytes } from "node:crypto";
import { open } from "node:fs/promises";
import { createServer } from "node:http";
import { prepareCliEvidence } from "./lan-receiver-evidence.mjs";

export const MAX_BODY_BYTES = 65_536;

const probeHtml = `<!doctype html>
<meta charset="utf-8">
<title>SIX Bridge VDtN HLS 검증</title>
<h1>HLS 감지 요청 완료</h1>
<script>fetch("./hls/master.m3u8").catch(() => {});</script>`;

const masterPlaylist = `#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:4,
segment.ts
#EXT-X-ENDLIST
`;

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

export function validateDownloadRequest(input) {
  if (input.method !== "POST") {
    return { ok: false, status: 405, reason: "method" };
  }
  if (input.pathname !== `/${input.runToken}/rest`) {
    return { ok: false, status: 404, reason: "route" };
  }
  if (input.search !== "") {
    return { ok: false, status: 400, reason: "query" };
  }
  if (input.authorization !== `Bearer ${input.bearerToken}`) {
    return { ok: false, status: 401, reason: "authorization" };
  }
  if (!/^application\/json(?:\s*;|$)/i.test(input.contentType)) {
    return { ok: false, status: 415, reason: "content-type" };
  }

  let body;
  try {
    body = JSON.parse(input.bodyText);
  } catch {
    return { ok: false, status: 400, reason: "json" };
  }

  if (
    body === null ||
    Array.isArray(body) ||
    typeof body !== "object" ||
    Object.keys(body).sort().join(",") !== "resolution,url"
  ) {
    return { ok: false, status: 400, reason: "body-keys" };
  }
  if (body.url !== input.expectedUrl) {
    return { ok: false, status: 400, reason: "url" };
  }
  if (body.resolution !== "best") {
    return { ok: false, status: 400, reason: "resolution" };
  }

  return {
    ok: true,
    status: 200,
    reason: "matched",
    url: body.url,
    resolution: body.resolution,
  };
}

export async function startVideoNasReceiver({
  bindHost,
  advertiseHost,
  port,
  mode,
  expectedUrl,
  timeoutMs,
  evidencePath,
  evidenceFile: providedEvidenceFile,
}) {
  const runToken = randomBytes(24).toString("hex");
  const bearerToken = randomBytes(24).toString("hex");
  const evidenceFile = providedEvidenceFile ?? await open(evidencePath, "wx", 0o600);
  let downloadUrl = expectedUrl;
  const sockets = new Set();
  let settled = false;
  let evidenceClosed = false;
  let serverClosed = false;
  let closePromise;
  let finalizationPromise;
  let timeoutHandle;
  let resolveCompleted;
  const completed = new Promise((resolve) => {
    resolveCompleted = resolve;
  });
  const closeEvidence = async () => {
    if (!evidenceClosed) {
      evidenceClosed = true;
      await evidenceFile.close();
    }
  };
  const writeEvidence = ({
    requestMatched,
    reason,
    receivedUrl = "",
    resolution = "",
    authMatched = false,
  }) => evidenceFile.writeFile(`${JSON.stringify({
    schemaVersion: 1,
    requestMatched,
    reason,
    method: "POST",
    route: "/<redacted>/rest",
    authMatched,
    expectedUrlSha256: sha256(downloadUrl),
    receivedUrlSha256: sha256(receivedUrl),
    resolution,
    completedAt: new Date().toISOString(),
  })}\n`, "utf8");
  const endResponse = (response) => new Promise((resolve) => {
    if (response.writableFinished || response.destroyed) {
      resolve();
      return;
    }
    const done = () => {
      response.off("finish", done);
      response.off("close", done);
      response.off("error", done);
      resolve();
    };
    response.once("finish", done);
    response.once("close", done);
    response.once("error", done);
    response.end("matched");
  });
  let startFinalization;
  const server = createServer((request, response) => {
    const requestUrl = new URL(request.url, "http://receiver.invalid");

    if (
      mode === "hls" &&
      request.method === "GET" &&
      requestUrl.search === "" &&
      requestUrl.pathname === `/${runToken}/probe.html`
    ) {
      response.writeHead(200, { "content-type": "text/html; charset=utf-8" });
      response.end(probeHtml);
      return;
    }

    if (
      mode === "hls" &&
      request.method === "GET" &&
      requestUrl.search === "" &&
      requestUrl.pathname === `/${runToken}/hls/master.m3u8`
    ) {
      response.writeHead(200, {
        "content-type": "application/vnd.apple.mpegurl; charset=utf-8",
      });
      response.end(masterPlaylist);
      return;
    }

    if (request.method === "GET") {
      response.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
      response.end("route");
      return;
    }

    const chunks = [];
    let bodyBytes = 0;
    let oversized = false;
    request.on("data", (chunk) => {
      if (oversized) {
        return;
      }
      bodyBytes += chunk.length;
      if (bodyBytes > MAX_BODY_BYTES) {
        oversized = true;
        chunks.length = 0;
        response.writeHead(413, {
          connection: "close",
          "content-type": "text/plain; charset=utf-8",
        });
        response.end("body-size");
        return;
      }
      chunks.push(chunk);
    });
    request.on("end", () => {
      if (oversized) {
        return;
      }
      const validation = validateDownloadRequest({
        method: request.method,
        pathname: requestUrl.pathname,
        search: requestUrl.search,
        authorization: request.headers.authorization ?? "",
        contentType: request.headers["content-type"] ?? "",
        bodyText: Buffer.concat(chunks).toString("utf8"),
        runToken,
        bearerToken,
        expectedUrl: downloadUrl,
      });

      if (!validation.ok) {
        response.writeHead(validation.status, {
          "content-type": "text/plain; charset=utf-8",
        });
        response.end(validation.reason);
        return;
      }
      const finalization = startFinalization({
        requestMatched: true,
        reason: "matched",
        receivedUrl: validation.url,
        resolution: validation.resolution,
        authMatched: true,
      }, response);
      if (!finalization) {
        response.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
        response.end("route");
        return;
      }
    });
  });
  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });

  const closeServer = async () => {
    if (serverClosed) {
      return;
    }
    serverClosed = true;
    if (!server.listening) {
      for (const socket of sockets) {
        socket.destroy();
      }
      return;
    }
    await new Promise((resolve) => {
      server.close(() => resolve());
      for (const socket of sockets) {
        socket.destroy();
      }
    });
  };
  const cleanup = async () => {
    if (!closePromise) {
      closePromise = (async () => {
        clearTimeout(timeoutHandle);
        await closeServer();
        await closeEvidence();
      })();
    }
    await closePromise;
  };

  startFinalization = (details, response) => {
    if (settled) {
      return null;
    }
    settled = true;
    clearTimeout(timeoutHandle);
    if (response) {
      response.writeHead(200, { "content-type": "text/plain; charset=utf-8" });
    }
    finalizationPromise = (async () => {
      let result = {
        requestMatched: details.requestMatched,
        reason: details.reason,
      };
      try {
        await writeEvidence(details);
      } catch {
        result = { requestMatched: false, reason: "evidence" };
      }
      if (response) {
        await endResponse(response);
      }
      try {
        await cleanup();
      } catch {
        result = { requestMatched: false, reason: "evidence" };
      }
      resolveCompleted(result);
      return result;
    })();
    return finalizationPromise;
  };

  try {
    await new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(port, bindHost, () => {
        server.off("error", reject);
        resolve();
      });
    });
  } catch (error) {
    await cleanup();
    throw error;
  }
  const address = server.address();
  if (mode === "hls") {
    downloadUrl = `http://${advertiseHost}:${address.port}/${runToken}/hls/master.m3u8`;
  }
  timeoutHandle = setTimeout(() => {
    void startFinalization({
      requestMatched: false,
      reason: "timeout",
    });
  }, timeoutMs);

  const close = async () => {
    const finalization = startFinalization({
      requestMatched: false,
      reason: "closed",
    }) ?? finalizationPromise;
    await finalization;
  };

  return {
    configUrl: `http://${advertiseHost}:${address.port}/${runToken}#${bearerToken}`,
    probeUrl: mode === "hls"
      ? `http://${advertiseHost}:${address.port}/${runToken}/probe.html`
      : null,
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
      throw new Error(
        "usage: --mode --advertise-host [--expected-url] --evidence "
        + "[--port] [--timeout-ms]",
      );
    }
    values[option] = value;
  }

  for (const requiredOption of [
    "--mode",
    "--advertise-host",
    "--evidence",
  ]) {
    if (!values[requiredOption]) {
      throw new Error(`missing required option: ${requiredOption}`);
    }
  }
  if (!["page", "hls"].includes(values["--mode"])) {
    throw new Error("--mode must be page or hls");
  }
  if (values["--mode"] === "page" && !values["--expected-url"]) {
    throw new Error("missing required option: --expected-url");
  }
  if (
    values["--mode"] === "hls" &&
    values["--expected-url"] !== undefined
  ) {
    throw new Error("--expected-url is not allowed in hls mode");
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
    mode: values["--mode"],
    advertiseHost: values["--advertise-host"],
    expectedUrl: values["--expected-url"],
    evidencePath: values["--evidence"],
    port,
    timeoutMs,
  };
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const evidence = await prepareCliEvidence(options.evidencePath);
  const receiver = await startVideoNasReceiver({
    bindHost: "0.0.0.0",
    ...options,
    ...evidence,
  });
  console.log(`configUrl=${receiver.configUrl}`);
  if (receiver.probeUrl !== null) {
    console.log(`probeUrl=${receiver.probeUrl}`);
  }
  const result = await receiver.completed;
  if (!result.requestMatched) {
    throw new Error(
      `receiver completed without a matched request: ${result.reason}`,
    );
  }
}

if (
  process.argv[1] &&
  new URL(`file://${process.argv[1]}`).href === import.meta.url
) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
