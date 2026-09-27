import crypto from "node:crypto";
import net from "node:net";
import { pathToFileURL } from "node:url";

export class SocketReader {
  constructor(socket) {
    this.buffer = Buffer.alloc(0);
    this.pending = [];
    this.waiters = [];
    this.failure = null;
    socket.on("data", (data) => {
      const waiter = this.waiters.shift();
      if (waiter) waiter.resolve(data);
      else this.pending.push(data);
    });
    socket.on("close", () => {
      this.failure ??= Object.assign(new Error("WebSocket closed early"), { stage: "remote close" });
      for (const waiter of this.waiters.splice(0)) waiter.reject(this.failure);
    });
    socket.on("error", (error) => {
      this.failure = error;
      for (const waiter of this.waiters.splice(0)) waiter.reject(error);
    });
  }

  async more() {
    if (this.pending.length) {
      this.buffer = Buffer.concat([this.buffer, this.pending.shift()]);
      return;
    }
    if (this.failure) throw this.failure;
    const data = await new Promise((resolve, reject) => this.waiters.push({ resolve, reject }));
    this.buffer = Buffer.concat([this.buffer, data]);
  }

  async read(length) {
    while (this.buffer.length < length) await this.more();
    const result = this.buffer.subarray(0, length);
    this.buffer = this.buffer.subarray(length);
    return result;
  }

  async until(delimiter) {
    while (true) {
      const index = this.buffer.indexOf(delimiter);
      if (index >= 0) return this.read(index + delimiter.length);
      if (this.buffer.length > 16384) throw new Error("WebSocket handshake headers exceeded 16 KiB");
      await this.more();
    }
  }

  async frame() {
    const header = await this.read(2);
    const fin = (header[0] & 0x80) !== 0;
    const reserved = (header[0] & 0x70) !== 0;
    const masked = (header[1] & 0x80) !== 0;
    const opcode = header[0] & 0x0f;
    if (!fin) throw new Error("server sent a fragmented frame; terminal server frames must be unfragmented");
    if (reserved) throw new Error("server set reserved WebSocket frame bits");
    if (masked) throw new Error("server sent a masked WebSocket frame");

    let length = header[1] & 0x7f;
    if (length === 126) {
      length = (await this.read(2)).readUInt16BE(0);
      if (length < 126) throw new Error("server used a non-canonical WebSocket frame length");
    } else if (length === 127) {
      const size = (await this.read(8)).readBigUInt64BE(0);
      if (size < 65536n) throw new Error("server used a non-canonical WebSocket frame length");
      if (size > 65536n) throw new Error("server frame exceeded the terminal limit");
      length = Number(size);
    }
    if (length > 65536) throw new Error("server frame exceeded the terminal limit");
    if (opcode >= 8 && length > 125) throw new Error("server sent an oversized WebSocket control frame");
    const payload = await this.read(length);
    if (opcode === 8 && payload.length === 1) throw new Error("server sent an invalid close payload");
    return { opcode, payload };
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await run().catch((error) => {
    const stage = error.stage ?? "initialization";
    console.error(`terminal production smoke failed at ${stage}: ${error.message}`);
    process.exitCode = 1;
  });
}

async function run() {
  const base = new URL(required("INFRADESK_BASE_URL"));
  const organizationId = required("INFRADESK_TERMINAL_TEST_ORGANIZATION_ID");
  const connectionId = required("INFRADESK_TERMINAL_TEST_CONNECTION_ID");
  let currentStage = "login";
  let socket;
  try {
    const login = await fetch(new URL("/api/v1/auth/login", base), {
      method: "POST",
      headers: { "content-type": "application/json" },
      signal: AbortSignal.timeout(10000),
      body: JSON.stringify({
        email: required("INFRADESK_TERMINAL_TEST_EMAIL"),
        password: required("INFRADESK_TERMINAL_TEST_PASSWORD"),
      }),
    });
    if (!login.ok) throw failure(`HTTP ${login.status}`);
    const cookies = login.headers.getSetCookie?.() ?? [login.headers.get("set-cookie") ?? ""];
    const cookie = cookies
      .find((value) => value.startsWith("infradesk_session="))
      ?.split(";", 1)[0];
    if (!cookie) throw failure("session cookie was not issued");

    const path = `/api/v1/organizations/${encodeURIComponent(organizationId)}/connections/${encodeURIComponent(connectionId)}/terminal`;
    const key = crypto.randomBytes(16).toString("base64");
    currentStage = "TCP connect";
    socket = net.createConnection(Number(base.port || 80), base.hostname);
    const reader = new SocketReader(socket);
    await withTimeout(new Promise((resolve, reject) => {
      socket.once("connect", resolve);
      socket.once("error", reject);
    }), 10000, currentStage);

    currentStage = "HTTP Upgrade";
    socket.write([
      `GET ${path} HTTP/1.1`,
      `Host: ${base.host}`,
      "Upgrade: websocket",
      "Connection: Upgrade",
      "Sec-WebSocket-Version: 13",
      `Sec-WebSocket-Key: ${key}`,
      "Sec-WebSocket-Protocol: infradesk-terminal-v1",
      `Origin: ${base.origin}`,
      `Cookie: ${cookie}`,
      "",
      "",
    ].join("\r\n"));
    const responseHeaders = (await withTimeout(reader.until(Buffer.from("\r\n\r\n")), 10000, currentStage))
      .toString("latin1");
    const status = responseHeaders.split("\r\n", 1)[0];
    if (!/^HTTP\/1\.1 101\b/.test(status)) {
      const contentLength = Number(responseHeaders.split("\r\n")
        .find((line) => line.toLowerCase().startsWith("content-length:"))?.split(":", 2)[1] ?? 0);
      let safeCode;
      if (contentLength > 0 && contentLength <= 8192) {
        const body = (await withTimeout(reader.read(contentLength), 2000, currentStage)).toString("utf8");
        safeCode = body.match(/\"code\"\s*:\s*\"([A-Z0-9_]+)\"/)?.[1];
      }
      throw failure(`${status}${safeCode ? ` (${safeCode})` : ""}`);
    }
    const expectedAccept = crypto.createHash("sha1").update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`).digest("base64");
    const acceptHeader = responseHeaders.split("\r\n").find((line) => line.toLowerCase().startsWith("sec-websocket-accept:"));
    if (acceptHeader?.split(":", 2)[1]?.trim() !== expectedAccept) throw failure("server returned an invalid Sec-WebSocket-Accept");

    currentStage = "subprotocol";
    const protocolHeader = responseHeaders.split("\r\n")
      .find((line) => line.toLowerCase().startsWith("sec-websocket-protocol:"));
    if (protocolHeader?.split(":", 2)[1]?.trim() !== "infradesk-terminal-v1") {
      throw failure("server did not negotiate infradesk-terminal-v1");
    }

    let ready = false;
    let output = Buffer.alloc(0);
    let sentInput = false;
    const deadline = Date.now() + 15000;
    while (Date.now() < deadline && !output.includes("terminal-smoke-ok")) {
      currentStage = ready ? "binary output timeout" : "ready";
      const frame = await withTimeout(reader.frame(), Math.max(1, deadline - Date.now()), currentStage);
      if (frame.opcode === 1) {
        let message;
        try { message = JSON.parse(frame.payload.toString("utf8")); }
        catch { throw failure("server sent malformed terminal control JSON"); }
        if (message.type === "ready" && message.protocolVersion === 1) ready = true;
        if (message.type === "error") {
          currentStage = "terminal error code";
          throw failure(`server returned ${String(message.code ?? "UNKNOWN")}`);
        }
      } else if (frame.opcode === 2) {
        output = Buffer.concat([output, frame.payload]);
      } else if (frame.opcode === 9) {
        socket.write(clientFrame(10, frame.payload));
      } else if (frame.opcode === 8) {
        currentStage = "remote close";
        const closeCode = frame.payload.length >= 2 ? frame.payload.readUInt16BE(0) : "none";
        const reason = frame.payload.length > 2 ? frame.payload.subarray(2).toString("utf8") : "";
        throw failure(`server closed WebSocket (code ${closeCode}${reason ? `, ${reason}` : ""})`);
      } else if (frame.opcode !== 10) {
        throw failure(`unexpected server frame opcode ${frame.opcode}`);
      }

      if (ready && !sentInput) {
        socket.write(clientFrame(1, Buffer.from('{"type":"resize","columns":100,"rows":35}')));
        socket.write(clientFrame(2, Buffer.from("printf 'terminal-smoke-%s\\n' ok\nexit\n")));
        sentInput = true;
      }
    }
    if (!ready) throw failure("server did not announce protocol version 1");
    if (!output.includes("terminal-smoke-ok")) {
      currentStage = "binary output timeout";
      throw failure("expected safe terminal marker was not received in a binary frame");
    }
  } catch (error) {
    if (error.stage) throw error;
    throw Object.assign(new Error(error.message), { stage: currentStage });
  } finally {
    socket?.destroy();
  }
}

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`missing ${name}`);
  return value;
}

function failure(message) {
  return new Error(message);
}

function clientFrame(opcode, payload) {
  const mask = crypto.randomBytes(4);
  let header;
  if (payload.length < 126) {
    header = Buffer.from([0x80 | opcode, 0x80 | payload.length]);
  } else if (payload.length <= 0xffff) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 0x80 | 126;
    header.writeUInt16BE(payload.length, 2);
  } else {
    throw new Error("CI terminal frame exceeded 65535 bytes");
  }
  const masked = Buffer.alloc(payload.length);
  for (let index = 0; index < payload.length; index += 1) masked[index] = payload[index] ^ mask[index % 4];
  return Buffer.concat([header, mask, masked]);
}

function withTimeout(promise, milliseconds, label) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${label} timed out`)), milliseconds);
    promise.then(
      (value) => { clearTimeout(timer); resolve(value); },
      (error) => { clearTimeout(timer); reject(error); },
    );
  });
}
