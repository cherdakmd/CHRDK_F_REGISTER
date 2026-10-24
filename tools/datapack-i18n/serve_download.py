#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Tiny single-file download server for the translated datapack.

Serves exactly one file and forces a browser download. Bind to 0.0.0.0 so the
Arena live-preview proxy (https://<port>-<sandbox>.e2b.app) can reach it.

    python3 serve_download.py --port 8000
"""

import argparse
import html
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_FILE = os.path.join(HERE, "..", "..", "datapack-ru", "mine-treasure-1.6.5-ru.zip")

FILE = ""
NAME = ""


class Handler(BaseHTTPRequestHandler):
    server_version = "MineTreasureRU/1.0"

    def do_GET(self):  # noqa: N802
        path = self.path.split("?", 1)[0].rstrip("/") or "/"
        if path in ("/", "/index.html"):
            self._index()
            return
        if path != "/" + NAME:
            self.send_error(404, "Not found")
            return
        try:
            with open(FILE, "rb") as fh:
                data = fh.read()
        except OSError:
            self.send_error(500, "File unavailable")
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Disposition", f'attachment; filename="{NAME}"')
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_HEAD(self):  # noqa: N802
        """Mirror the GET headers so download managers and browsers can probe it."""
        path = self.path.split("?", 1)[0].rstrip("/") or "/"
        if path in ("/", "/index.html"):
            size = len(self._index_body())
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            return
        if path != "/" + NAME:
            self.send_error(404, "Not found")
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Length", str(os.path.getsize(FILE)))
        self.send_header("Content-Disposition", f'attachment; filename="{NAME}"')
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

    def _index_body(self) -> bytes:
        size = os.path.getsize(FILE) / 1024 / 1024
        safe = html.escape(NAME)
        return f"""<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><title>Mine Treasure 1.6.5 — RU</title>
<style>body{{font:16px/1.5 system-ui,sans-serif;max-width:640px;margin:60px auto;padding:0 20px;color:#1b1b1f}}
a{{display:inline-block;padding:12px 20px;background:#2f6feb;color:#fff;border-radius:8px;text-decoration:none}}
code{{background:#f1f1f4;padding:2px 6px;border-radius:4px}}</style></head>
<body><h1>Mine Treasure 1.6.5 — русский перевод</h1>
<p>Готовый датапак: <b>{safe}</b> ({size:.1f} МБ).</p>
<p><a href="/{safe}">Скачать ZIP</a></p>
<p>Прямая ссылка: <code>/{safe}</code></p>
<p>Установка: положить ZIP в <code>&lt;мир&gt;/datapacks/</code>, затем <code>/datapack enable</code> и <code>/reload</code>.</p>
</body></html>""".encode("utf-8")

    def _index(self):
        body = self._index_body()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("[download] " + fmt % args, flush=True)


def main():
    global FILE, NAME
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--file", default=DEFAULT_FILE)
    args = ap.parse_args()
    FILE = os.path.abspath(args.file)
    NAME = os.path.basename(FILE)
    if not os.path.isfile(FILE):
        raise SystemExit(f"File not found: {FILE}")
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"Serving {FILE} on 0.0.0.0:{args.port}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
