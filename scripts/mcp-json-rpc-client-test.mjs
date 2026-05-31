import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";
import { mcpToolRequest, parseMcpToolResponse, createMcpJsonRpcClient } from "./mcp-json-rpc-client.mjs";

test("mcpToolRequest builds a tools/call JSON-RPC request", () => {
  assert.deepEqual(mcpToolRequest(7, "fixthis_open_feedback_console", { packageName: "com.example.reply" }), {
    jsonrpc: "2.0",
    id: 7,
    method: "tools/call",
    params: {
      name: "fixthis_open_feedback_console",
      arguments: { packageName: "com.example.reply" },
    },
  });
});

test("parseMcpToolResponse decodes JSON text content", () => {
  assert.deepEqual(parseMcpToolResponse({
    id: 1,
    result: { content: [{ type: "text", text: "{\"sessionId\":\"session-1\"}" }] },
  }), { sessionId: "session-1" });
});

test("parseMcpToolResponse throws MCP errors and missing text", () => {
  assert.throws(() => parseMcpToolResponse({ error: { code: -32602, message: "bad" } }), /bad/);
  assert.throws(() => parseMcpToolResponse({ result: { content: [] } }), /text content/);
});

test("createMcpJsonRpcClient initializes, calls tools, and closes child process", async () => {
  const writes = [];
  const stdout = new EventEmitter();
  stdout.setEncoding = () => {};
  const stderr = new EventEmitter();
  stderr.setEncoding = () => {};
  const child = new EventEmitter();
  child.stdout = stdout;
  child.stderr = stderr;
  child.stdin = {
    write: (line) => {
      writes.push(JSON.parse(line));
      const request = writes.at(-1);
      if (request.method === "initialize") {
        queueMicrotask(() => stdout.emit("line", JSON.stringify({ id: request.id, result: {} })));
      }
      if (request.method === "tools/call") {
        queueMicrotask(() => stdout.emit("line", JSON.stringify({
          id: request.id,
          result: { content: [{ type: "text", text: "{\"ok\":true}" }] },
        })));
      }
    },
    end: () => { child.stdinEnded = true; },
  };
  child.kill = (signal) => { child.killedWith = signal; child.emit("close", 0); };

  const client = await createMcpJsonRpcClient({
    command: "fixthis-mcp",
    args: ["--project-dir", "."],
    spawnFn: () => child,
    readlineFactory: ({ input }) => input,
    clientInfo: { name: "test-client", version: "0" },
  });

  assert.equal(writes[1].method, "notifications/initialized");
  assert.deepEqual(await client.callTool("fixthis_status", {}), { ok: true });
  await client.close();
  assert.equal(child.stdinEnded, true);
  assert.equal(child.killedWith, "SIGTERM");
});

test("createMcpJsonRpcClient times out pending tool calls with stderr context", async () => {
  const stdout = new EventEmitter();
  stdout.setEncoding = () => {};
  const stderr = new EventEmitter();
  stderr.setEncoding = () => {};
  const child = new EventEmitter();
  child.stdout = stdout;
  child.stderr = stderr;
  child.stdin = {
    write: (line) => {
      const request = JSON.parse(line);
      if (request.method === "initialize") {
        queueMicrotask(() => stdout.emit("line", JSON.stringify({ id: request.id, result: {} })));
      }
      if (request.method === "tools/call") {
        queueMicrotask(() => stderr.emit("data", "mcp waiting forever"));
      }
    },
    end: () => {},
  };
  child.kill = () => { child.emit("close", 0); };

  const client = await createMcpJsonRpcClient({
    command: "fixthis-mcp",
    spawnFn: () => child,
    readlineFactory: ({ input }) => input,
  });

  await assert.rejects(() => client.callTool("fixthis_status", {}, 5), /Timed out.*mcp waiting forever/s);
  await client.close();
});

test("createMcpJsonRpcClient rejects MCP startup spawn errors", async () => {
  const stdout = new EventEmitter();
  stdout.setEncoding = () => {};
  const stderr = new EventEmitter();
  stderr.setEncoding = () => {};
  const child = new EventEmitter();
  child.stdout = stdout;
  child.stderr = stderr;
  child.stdin = {
    write: () => {},
    end: () => {},
  };
  child.kill = () => {};

  await assert.rejects(
    () => createMcpJsonRpcClient({
      command: "missing-fixthis-mcp",
      spawnFn: () => {
        queueMicrotask(() => child.emit("error", Object.assign(new Error("spawn missing-fixthis-mcp ENOENT"), { code: "ENOENT" })));
        return child;
      },
      readlineFactory: ({ input }) => input,
      initializeTimeoutMs: 50,
    }),
    /MCP process failed to start.*ENOENT/s,
  );
});
