import assert from "node:assert/strict";
import crypto from "node:crypto";
import { EventEmitter, once } from "node:events";
import http from "node:http";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { SocketReader } from "./check-terminal-websocket.mjs";

function serverFrame(opcode, payload) {
  let header;
  if (payload.length < 126) header = Buffer.from([0x80 | opcode, payload.length]);
  else if (payload.length <= 65535) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(payload.length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(payload.length), 2);
  }
  return Buffer.concat([header, payload]);
}

function readerFor(bytes) {
  const socket = new EventEmitter();
  const reader = new SocketReader(socket);
  socket.emit("data", bytes);
  return { reader, socket };
}

for (const length of [16, 126, 65536]) {
  test(`reads unmasked server binary frame of ${length} bytes`, async () => {
    const payload = Buffer.alloc(length, 97);
    const { reader } = readerFor(serverFrame(2, payload));
    const frame = await reader.frame();
    assert.equal(frame.opcode, 2);
    assert.deepEqual(frame.payload, payload);
  });
}

test("rejects fragmented, masked, reserved-bit and invalid close frames", async () => {
  for (const frame of [Buffer.from([0x02, 0]), Buffer.from([0x82, 0x80]), Buffer.from([0xc2, 0]), Buffer.from([0x88, 1, 0])]) {
    await assert.rejects(readerFor(frame).reader.frame());
  }
});

test("parses a close payload and detects a socket that closed before the next read", async () => {
  const payload = Buffer.alloc(2);
  payload.writeUInt16BE(1000);
  const { reader, socket } = readerFor(serverFrame(8, Buffer.concat([payload, Buffer.from("REMOTE_EOF")])));
  socket.emit("close");
  const frame = await reader.frame();
  assert.equal(frame.opcode, 8);
  assert.equal(frame.payload.readUInt16BE(0), 1000);
  assert.equal(frame.payload.subarray(2).toString(), "REMOTE_EOF");
  await assert.rejects(reader.frame(), { stage: "remote close" });
});

test("smoke executable logs in, upgrades with matching Origin and exchanges terminal frames", { timeout: 10000 }, async () => {
  const sockets = new Set();
  let handshakeValid = false;
  let receivedInput = false;
  let terminalSocket;
  const server = http.createServer((request, response) => {
    request.resume();
    response.writeHead(200, { "Set-Cookie": "infradesk_session=fixture-session; HttpOnly", "Content-Type": "application/json" });
    response.end("{}");
    if (request.url === "/api/v1/auth/logout") {
      assert.equal(request.headers.cookie, "infradesk_session=fixture-session");
      terminalSocket.write(serverFrame(1, Buffer.from('{"type":"closed","protocolVersion":1,"code":"AUTH_SESSION_ENDED"}')));
      const close = Buffer.alloc(2 + Buffer.byteLength("AUTH_SESSION_ENDED"));
      close.writeUInt16BE(1008);
      close.write("AUTH_SESSION_ENDED", 2);
      terminalSocket.write(serverFrame(8, close));
    }
  });
  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });
  server.on("upgrade", (request, socket) => {
    terminalSocket = socket;
    handshakeValid = request.headers.origin === `http://${request.headers.host}` &&
      request.headers.cookie === "infradesk_session=fixture-session" &&
      request.headers["sec-websocket-protocol"] === "infradesk-terminal-v1";
    const accept = crypto.createHash("sha1")
      .update(`${request.headers["sec-websocket-key"]}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`).digest("base64");
    socket.write([
      "HTTP/1.1 101 Switching Protocols", "Upgrade: websocket", "Connection: Upgrade",
      `Sec-WebSocket-Accept: ${accept}`, "Sec-WebSocket-Protocol: infradesk-terminal-v1", "", "",
    ].join("\r\n"));
    socket.write(serverFrame(1, Buffer.from('{"type":"ready","protocolVersion":1,"sessionId":"00000000-0000-0000-0000-000000000001"}')));
    let buffered = Buffer.alloc(0);
    socket.on("data", (chunk) => {
      buffered = Buffer.concat([buffered, chunk]);
      while (buffered.length >= 6) {
        const length = buffered[1] & 0x7f;
        if (length >= 126 || buffered.length < 6 + length) return;
        const opcode = buffered[0] & 0x0f;
        buffered = buffered.subarray(6 + length);
        if (opcode === 2 && !receivedInput) {
          receivedInput = true;
          socket.write(serverFrame(2, Buffer.from("terminal-smoke-ok\n")));
        }
      }
    });
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    const child = spawn(process.execPath, [fileURLToPath(new URL("./check-terminal-websocket.mjs", import.meta.url))], {
      env: {
        ...process.env,
        INFRADESK_BASE_URL: `http://127.0.0.1:${server.address().port}`,
        INFRADESK_TERMINAL_TEST_ORGANIZATION_ID: "fixture-org",
        INFRADESK_TERMINAL_TEST_CONNECTION_ID: "fixture-connection",
        INFRADESK_TERMINAL_TEST_EMAIL: "fixture@example.test",
        INFRADESK_TERMINAL_TEST_PASSWORD: "fixture-password",
      },
      windowsHide: true,
    });
    let diagnostics = "";
    child.stderr.on("data", (data) => { diagnostics += data.toString(); });
    const [code] = await once(child, "close");
    assert.equal(code, 0, diagnostics);
    assert.equal(handshakeValid, true);
    assert.equal(receivedInput, true);
    assert.equal(diagnostics, "");
  } finally {
    for (const socket of sockets) socket.destroy();
    await new Promise((resolve) => server.close(resolve));
  }
});
