import crypto from "node:crypto";
import net from "node:net";

const base = new URL(required("INFRADESK_BASE_URL"));
const organizationId = required("INFRADESK_TERMINAL_TEST_ORGANIZATION_ID");
const connectionId = required("INFRADESK_TERMINAL_TEST_CONNECTION_ID");

const login = await fetch(new URL("/api/v1/auth/login", base), {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({
    email: required("INFRADESK_TERMINAL_TEST_EMAIL"),
    password: required("INFRADESK_TERMINAL_TEST_PASSWORD"),
  }),
});
if (!login.ok) throw new Error(`terminal smoke login failed with HTTP ${login.status}`);
const cookies = login.headers.getSetCookie?.() ?? [login.headers.get("set-cookie") ?? ""];
const cookie = cookies
  .find((value) => value.startsWith("infradesk_session="))
  ?.split(";", 1)[0];
if (!cookie) throw new Error("terminal smoke login did not issue a session cookie");

const path = `/api/v1/organizations/${organizationId}/connections/${connectionId}/terminal`;
const host = base.host;
const key = crypto.randomBytes(16).toString("base64");
const socket = net.createConnection(Number(base.port || 80), base.hostname);
const reader = new SocketReader(socket);

try {
  await withTimeout(new Promise((resolve, reject) => {
    socket.once("connect", resolve);
    socket.once("error", reject);
  }), 10000, "proxy connection");

  socket.write([
    `GET ${path} HTTP/1.1`,
    `Host: ${host}`,
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

  const headers = (await withTimeout(reader.until(Buffer.from("\r\n\r\n")), 10000, "WebSocket upgrade"))
    .toString("latin1");
  if (!/^HTTP\/1\.1 101\b/.test(headers)) {
    throw new Error(`production proxy did not upgrade the terminal WebSocket: ${headers.split("\r\n")[0]}`);
  }
  if (!headers.toLowerCase().includes("sec-websocket-protocol: infradesk-terminal-v1")) {
    throw new Error("production proxy did not negotiate the terminal protocol");
  }

  let ready = false;
  let output = Buffer.alloc(0);
  let sentInput = false;
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline && !output.includes("terminal-smoke-ok")) {
    const frame = await withTimeout(reader.frame(), Math.max(1, deadline - Date.now()), "terminal frames");
    if (frame.opcode === 1) {
      const message = JSON.parse(frame.payload.toString("utf8"));
      if (message.type === "ready" && message.protocolVersion === 1) ready = true;
      if (message.type === "error") throw new Error(`terminal returned safe error ${message.code}`);
    } else if (frame.opcode === 2) {
      output = Buffer.concat([output, frame.payload]);
    } else if (frame.opcode === 9) {
      socket.write(clientFrame(10, frame.payload));
    } else if (frame.opcode === 8) {
      break;
    }

    if (ready && !sentInput) {
      socket.write(clientFrame(1, Buffer.from('{"type":"resize","columns":100,"rows":35}')));
      socket.write(clientFrame(2, Buffer.from("printf 'terminal-smoke-%s\\n' ok\nexit\n")));
      sentInput = true;
    }
  }
  if (!ready) throw new Error("terminal did not announce protocol version 1");
  if (!output.includes("terminal-smoke-ok")) throw new Error("terminal SSH output did not traverse the production proxy");
} finally {
  socket.destroy();
}

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`missing ${name}`);
  return value;
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
    throw new Error("CI terminal test frame unexpectedly exceeded 65535 bytes");
  }
  const masked = Buffer.alloc(payload.length);
  for (let index = 0; index < payload.length; index += 1) masked[index] = payload[index] ^ mask[index % 4];
  return Buffer.concat([header, mask, masked]);
}

function withTimeout(promise, milliseconds, label) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${label} timed out`)), milliseconds);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}

class SocketReader {
  constructor(socket) {
    this.buffer = Buffer.alloc(0);
    this.pending = [];
    this.waiters = [];
    socket.on("data", (data) => {
      const waiter = this.waiters.shift();
      if (waiter) waiter.resolve(data);
      else this.pending.push(data);
    });
    socket.on("close", () => {
      for (const waiter of this.waiters.splice(0)) waiter.reject(new Error("WebSocket closed early"));
    });
    socket.on("error", (error) => {
      for (const waiter of this.waiters.splice(0)) waiter.reject(error);
    });
  }

  async more() {
    if (this.pending.length) {
      this.buffer = Buffer.concat([this.buffer, this.pending.shift()]);
      return;
    }
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
      await this.more();
    }
  }

  async frame() {
    const header = await this.read(2);
    const opcode = header[0] & 0x0f;
    let length = header[1] & 0x7f;
    if (length === 126) length = (await this.read(2)).readUInt16BE(0);
    else if (length === 127) {
      const size = (await this.read(8)).readBigUInt64BE(0);
      if (size > 65536n) throw new Error("server frame exceeded the terminal limit");
      length = Number(size);
    }
    if (length > 65536) throw new Error("server frame exceeded the terminal limit");
    return { opcode, payload: await this.read(length) };
  }
}
