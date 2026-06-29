import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { createServer } from "node:http";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(fileURLToPath(new URL(".", import.meta.url)), "dist");
const contentTypes = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".ico": "image/x-icon",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".woff2": "font/woff2",
};

function responseHeaders(path) {
  return {
    "Cache-Control": path.includes("/assets/")
      ? "public, max-age=31536000, immutable"
      : "no-cache",
    "Content-Security-Policy":
      "default-src 'self'; connect-src 'self'; img-src 'self' data:; " +
      "style-src 'self' 'unsafe-inline'; script-src 'self'; font-src 'self' data:",
    "Referrer-Policy": "same-origin",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
  };
}

async function serveFile(requestPath, response) {
  const safePath = normalize(requestPath).replace(/^(\.\.[/\\])+/, "");
  const candidate = join(root, safePath);
  const target = candidate.startsWith(root) ? candidate : join(root, "index.html");
  let file = target;
  try {
    const metadata = await stat(file);
    if (!metadata.isFile()) file = join(root, "index.html");
  } catch {
    file = join(root, "index.html");
  }
  response.writeHead(200, {
    "Content-Type": contentTypes[extname(file)] || "application/octet-stream",
    ...responseHeaders(requestPath),
  });
  createReadStream(file).pipe(response);
}

createServer(async (request, response) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    response.writeHead(405, { Allow: "GET, HEAD" });
    response.end();
    return;
  }
  const url = new URL(request.url || "/", "http://localhost");
  if (url.pathname === "/healthz") {
    response.writeHead(200, {
      "Content-Type": "text/plain; charset=utf-8",
      ...responseHeaders(url.pathname),
    });
    response.end("ok\n");
    return;
  }
  await serveFile(decodeURIComponent(url.pathname.slice(1)), response);
}).listen(5173, "0.0.0.0");
