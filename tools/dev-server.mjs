// Serves the web version locally: node tools/dev-server.mjs
import { createReadStream, existsSync, statSync } from "node:fs";
import { createServer } from "node:http";
import { extname, join, normalize } from "node:path";

const root = join(import.meta.dirname, "..", "public");
const port = Number(process.env.PORT || 8088);
const types = { ".css": "text/css", ".html": "text/html", ".js": "text/javascript", ".json": "application/json", ".svg": "image/svg+xml", ".webmanifest": "application/manifest+json" };

createServer((request, response) => {
  const pathname = new URL(request.url, "http://localhost").pathname;
  let file = join(root, normalize(decodeURIComponent(pathname)).replace(/^(\.\.[/\\])+/, ""));
  if (!file.startsWith(root) || !existsSync(file) || statSync(file).isDirectory()) file = join(root, "index.html");
  response.writeHead(200, { "content-type": `${types[extname(file)] || "application/octet-stream"}; charset=utf-8`, "cache-control": "no-cache" });
  createReadStream(file).pipe(response);
}).listen(port, "127.0.0.1", () => console.log(`Color Clock: http://127.0.0.1:${port}`));
