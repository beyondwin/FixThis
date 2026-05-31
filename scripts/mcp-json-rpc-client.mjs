import { spawn } from "node:child_process";
import { createInterface } from "node:readline";

export function mcpToolRequest(id, name, args) {
  return {
    jsonrpc: "2.0",
    id,
    method: "tools/call",
    params: { name, arguments: args },
  };
}

export function parseMcpToolResponse(message) {
  if (message.error) {
    throw new Error(message.error.message || JSON.stringify(message.error));
  }
  const text = message.result?.content?.find((entry) => entry.type === "text")?.text;
  if (!text) throw new Error("MCP response did not include text content");
  return JSON.parse(text);
}

export async function createMcpJsonRpcClient({
  command,
  args = [],
  cwd,
  env = process.env,
  spawnFn = spawn,
  readlineFactory = createInterface,
  clientInfo = { name: "fixthis-smoke", version: "0" },
  initializeTimeoutMs = 30_000,
} = {}) {
  if (!command) throw new Error("MCP command is required");
  const child = spawnFn(command, args, {
    cwd,
    env,
    stdio: ["pipe", "pipe", "pipe"],
  });
  const stderr = [];
  child.stderr?.setEncoding?.("utf8");
  child.stderr?.on?.("data", (chunk) => stderr.push(chunk));
  child.stdout?.setEncoding?.("utf8");
  const rl = readlineFactory({ input: child.stdout });
  const pending = new Map();
  let nextId = 1;
  let didClose = false;
  let processFailure = null;
  function errorWithStderr(prefix, error) {
    const stderrText = stderr.join("").trim();
    const detail = error?.message || String(error);
    return new Error(`${prefix}: ${detail}${stderrText ? `\n${stderrText}` : ""}`);
  }
  function rejectPending(error) {
    for (const [id, slot] of pending) {
      clearTimeout(slot.timeout);
      pending.delete(id);
      slot.reject(error);
    }
  }
  const closed = new Promise((resolve) => child.once?.("close", (...args) => {
    didClose = true;
    if (pending.size > 0) {
      const [code, signal] = args;
      rejectPending(new Error(`MCP process closed before response: code=${code ?? "unknown"} signal=${signal ?? "none"}${stderr.join("").trim() ? `\n${stderr.join("").trim()}` : ""}`));
    }
    resolve(...args);
  }));
  child.once?.("error", (error) => {
    processFailure = errorWithStderr("MCP process failed to start", error);
    rejectPending(processFailure);
  });

  rl.on("line", (line) => {
    if (!line.trim()) return;
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      return;
    }
    const slot = pending.get(message.id);
    if (!slot) return;
    pending.delete(message.id);
    slot.resolve(message);
  });

  function send(message) {
    if (processFailure) throw processFailure;
    if (didClose) throw new Error("MCP process is closed");
    child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  async function request(message, timeoutMs = 30_000) {
    const id = message.id;
    const promise = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`Timed out waiting for MCP response ${id}: ${stderr.join("").trim()}`));
      }, timeoutMs);
      pending.set(id, {
        timeout,
        resolve: (value) => {
          clearTimeout(timeout);
          resolve(value);
        },
        reject,
      });
    });
    try {
      send(message);
    } catch (error) {
      const slot = pending.get(id);
      if (slot) {
        clearTimeout(slot.timeout);
        pending.delete(id);
      }
      throw error;
    }
    return promise;
  }

  await request({
    jsonrpc: "2.0",
    id: nextId++,
    method: "initialize",
    params: {
      protocolVersion: "2024-11-05",
      clientInfo,
      capabilities: {},
    },
  }, initializeTimeoutMs);
  send({ jsonrpc: "2.0", method: "notifications/initialized", params: {} });

  return {
    async callTool(name, toolArgs, timeoutMs) {
      return parseMcpToolResponse(await request(mcpToolRequest(nextId++, name, toolArgs), timeoutMs));
    },
    async close() {
      rl.close?.();
      child.stdin.end();
      child.kill?.("SIGTERM");
      await Promise.race([
        closed,
        new Promise((resolve) => setTimeout(resolve, 5_000)),
      ]);
      if (!didClose && !child.killed) child.kill?.("SIGKILL");
    },
  };
}
