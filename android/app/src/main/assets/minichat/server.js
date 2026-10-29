// Self-contained custom-endpoint chat server (no Codex/OpenClaw required).
// Serves a chat UI and proxies chat completions to any OpenAI-compatible
// endpoint configured via MERCURY_ENDPOINT/MERCURY_API_KEY (from env or
// $HOME/.mercury.env).
"use strict";
const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = parseInt(process.env.MINICHAT_PORT || "18925", 10);
const HOME = process.env.HOME || process.env.HOME_DIR || "/data/data/com.codex.mobile/files/home";

function readConfig() {
  let endpoint = process.env.MERCURY_ENDPOINT || "";
  let apiKey = process.env.MERCURY_API_KEY || "";
  let model = process.env.MERCURY_MODEL || "mercury-3";
  if (!endpoint || !apiKey) {
    try {
      const p = path.join(HOME, ".mercury.env");
      if (fs.existsSync(p)) {
        for (const raw of fs.readFileSync(p, "utf8").split("\n")) {
          const line = raw.trim();
          if (!line || line.startsWith("#")) continue;
          const idx = line.indexOf("=");
          if (idx <= 0) continue;
          const k = line.slice(0, idx).trim();
          const v = line.slice(idx + 1).trim();
          if (k === "MERCURY_ENDPOINT" && !endpoint) endpoint = v;
          else if (k === "MERCURY_API_KEY" && !apiKey) apiKey = v;
          else if (k === "MERCURY_MODEL") model = v;
        }
      }
    } catch (e) {}
  }
  return { endpoint, apiKey, model };
}

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    "Content-Type": "application/json; charset=utf-8",
    "Access-Control-Allow-Origin": "*",
    "Content-Length": Buffer.byteLength(body),
  });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://127.0.0.1");
  const pathname = url.pathname;
  const cfg = readConfig();

  if (req.method === "OPTIONS") {
    res.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST,GET,OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type,Authorization",
    });
    return res.end();
  }

  if (req.method === "GET" && pathname === "/") {
    const html = path.join(__dirname, "index.html");
    if (fs.existsSync(html)) {
      const data = fs.readFileSync(html);
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Content-Length": data.length });
      return res.end(data);
    }
    return sendJson(res, 500, { error: { message: "index.html missing" } });
  }

  if (req.method === "GET" && pathname === "/v1/models") {
    return sendJson(res, 200, { models: [{ id: cfg.model, name: cfg.model }] });
  }

  if (req.method === "POST" && pathname === "/v1/chat/completions") {
    if (!cfg.endpoint || !cfg.apiKey) {
      return sendJson(res, 400, { error: { message: "Custom endpoint not configured (set Base URL + API Key in app settings)" } });
    }
    let body = "";
    for await (const chunk of req) body += chunk;
    let parsed;
    try {
      parsed = JSON.parse(body);
    } catch (e) {
      return sendJson(res, 400, { error: { message: "Bad JSON" } });
    }
    const model = (parsed.model || cfg.model || "mercury-3");
    const messages = Array.isArray(parsed.messages) ? parsed.messages : [];
    const stream = parsed.stream !== false;

    const upstreamBody = JSON.stringify({
      model,
      messages,
      stream,
      ...(parsed.temperature !== undefined ? { temperature: parsed.temperature } : {}),
      ...(parsed.max_tokens !== undefined ? { max_tokens: parsed.max_tokens } : {}),
    });

    try {
      const up = await fetch(cfg.endpoint, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer " + cfg.apiKey,
        },
        body: upstreamBody,
      });

      const contentType = up.headers.get("content-type") || "";
      if (!up.ok) {
        const text = await up.text();
        return sendJson(res, up.status, { error: { message: `Upstream ${up.status}: ${text.slice(0, 500)}` } });
      }

      if (stream) {
        res.writeHead(200, {
          "Content-Type": "text/event-stream; charset=utf-8",
          "Cache-Control": "no-cache",
          "Access-Control-Allow-Origin": "*",
          Connection: "keep-alive",
        });
        const reader = up.body.getReader();
        const dec = new TextDecoder();
        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            res.write(dec.decode(value, { stream: true }));
          }
          res.end();
        } catch (e) {
          res.end();
        }
      } else {
        const text = await up.text();
        res.writeHead(200, {
          "Content-Type": contentType || "application/json",
          "Access-Control-Allow-Origin": "*",
          "Content-Length": Buffer.byteLength(text),
        });
        res.end(text);
      }
    } catch (e) {
      return sendJson(res, 502, { error: { message: "Upstream error: " + (e && e.message ? e.message : String(e)) } });
    }
    return;
  }

  sendJson(res, 404, { error: { message: "Not found" } });
});

server.on("error", (e) => {
  // Surface startup errors to a file the Android side can read and show.
  try {
    const errFile = path.join(HOME, "minichat/error.log");
    fs.writeFileSync(errFile, (e && e.stack) || String(e));
  } catch (_) {}
  console.error("server error:", e);
});

server.listen(PORT, "127.0.0.1", () => {
  console.log("minichat server on http://127.0.0.1:" + PORT);
});
