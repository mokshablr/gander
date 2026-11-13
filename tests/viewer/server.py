"""
A stand-in for the Android side of the viewer.

ViewerActivity serves the open document to the WebView out of
shouldInterceptRequest, and serves everything else from the APK's assets
through WebViewAssetLoader. This does the same two things over HTTP so the
shipped pages run unmodified: same paths, same query parameters, same range
semantics.

The range behaviour mirrors ViewerActivity.docResponse and DocRange.kt. The
one deliberate difference is that whether ranges are offered is set per test
rather than derived from the file size: the 16 MiB threshold itself is pinned
in DocRangeTest, and forcing a small fixture down the ranged path is the only
way to exercise it here in under a second.
"""

import io
import json
import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote

REPO = Path(__file__).resolve().parents[2]
VIEWER = REPO / "app/src/main/assets/viewer"
FIXTURES = REPO / "tests/fixtures/files"

# Content types the pages depend on. .mjs above all: pdf.html imports
# lib/pdf.min.mjs as a module, and a module served as anything but JavaScript
# is refused by the browser with nothing in the page to say why.
CONTENT_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json",
    ".bcmap": "application/octet-stream",
    ".pdf": "application/pdf",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".gif": "image/gif",
    ".svg": "image/svg+xml",
    ".wav": "audio/wav",
    ".md": "text/markdown",
    ".txt": "text/plain",
    ".csv": "text/csv",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
}


def parse_range(header, total):
    """ViewerActivity.parseRange, to the letter. None means serve it whole."""
    if total <= 0:
        return None
    if "bytes=" not in header:
        return None
    spec = header.split("bytes=", 1)[1].split(",", 1)[0].strip()
    if not spec:
        return None
    first, _, last = spec.partition("-")
    try:
        start = int(first.strip())
    except ValueError:
        return None
    try:
        end = int(last.strip())
    except ValueError:
        end = total - 1
    if start < 0 or start > end or start >= total:
        return None
    return start, min(end, total - 1)


@dataclass
class Simple:
    """
    What the simple view's isolated renderer answers, standing in for SimplePdf.kt: each page's
    size in points, or the word /render/document gives in place of a document. A page is drawn
    with Pillow as white paper with a band across its top in a colour of its own, see colour(),
    and as the paper turned over for night=1, which Gander does to the real pages.
    """
    sizes: list = field(default_factory=list)
    pages: int = 0                              # more pages than sizes, each the last size
    error: str = None
    broken: set = field(default_factory=set)   # pages, one based, the renderer can't draw
    delay: float = 0.0                          # how long each page takes to draw, in seconds
    gate: threading.Event = None                # if given, sizes are answered once it is set
    drawing: int = 0                            # pages being drawn now
    most: int = 0                               # the most ever drawn at once
    lock: threading.Lock = field(default_factory=threading.Lock)

    @property
    def count(self):
        return max(self.pages, len(self.sizes))

    def size(self, n):
        """Page n's size, one based."""
        return self.sizes[min(n, len(self.sizes)) - 1]

    @staticmethod
    def colour(n):
        """The band across the top of page n, one based, a colour no other page near it has."""
        return ((n * 67) % 200 + 30, (n * 131) % 200 + 30, (n * 29) % 200 + 30)


@dataclass
class Served:
    """What the page is currently being shown."""
    path: Path = None
    ranged: bool = False
    status_override: int = 0
    # The code page DocumentServer would guess the document is in, see TextEncoding.kt
    encoding: str = None
    requests: list = field(default_factory=list)
    simple: Simple = None


class ViewerServer:
    def __init__(self):
        self.state = Served()
        state = self.state

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass  # the test output is quiet unless something fails

            def _send(self, status, body, headers):
                # Recorded against the request this is answering, so a test can
                # tell "asked for and got it" from "asked for and got a 404".
                # Held on the handler rather than read back as the last entry in
                # the log: requests are answered on parallel threads, and pdf.js
                # asks for a CMap, a decoder and a range of the document at once,
                # so the last entry can belong to a request that came in after.
                record = getattr(self, "record", None)
                if record is not None:
                    record["status"] = status
                    record["bytes"] = len(body)
                self.send_response(status)
                for k, v in headers.items():
                    self.send_header(k, v)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                path, _, query = self.path.partition("?")
                self.record = {
                    "path": path,
                    "query": {k: v[0] for k, v in parse_qs(query).items()},
                    "range": self.headers.get("Range"),
                    "status": None,
                    "bytes": 0,
                }
                state.requests.append(self.record)

                if path.startswith("/doc/"):
                    return self._document()
                if path.startswith("/render/"):
                    return self._render(path, self.record["query"])
                if path.startswith("/assets/viewer/"):
                    return self._asset(path[len("/assets/viewer/"):])
                if path == "/beside":
                    return self._beside(self.record["query"].get("path", ""))
                self._send(404, b"not found", {"Content-Type": "text/plain"})

            def _asset(self, relative):
                target = (VIEWER / relative).resolve()
                # The asset loader serves out of one directory and no further
                if not str(target).startswith(str(VIEWER)) or not target.is_file():
                    return self._send(404, b"no such asset", {"Content-Type": "text/plain"})
                ctype = CONTENT_TYPES.get(target.suffix, "application/octet-stream")
                self._send(200, target.read_bytes(), {"Content-Type": ctype})

            def _beside(self, src):
                """
                A picture a Markdown file names from its folder, as PicturesBeside finds it for a
                file opened from a folder added in Gander, here the document's own folder. The
                rules for the path itself are pinned in PicturesBesideTest.
                """
                folder = state.path.parent.resolve() if state.path else None
                target = (folder / unquote(src.split("#")[0].split("?")[0])).resolve() if folder else None
                ctype = CONTENT_TYPES.get(target.suffix, "") if target else ""
                if not (target and target.is_relative_to(folder) and target.is_file()
                        and ctype.startswith("image/")):
                    return self._send(404, b"no such picture", {"Content-Type": "text/plain"})
                self._send(200, target.read_bytes(), {"Content-Type": ctype, "X-Content-Type-Options": "nosniff"})

            def _json(self, said):
                body = json.dumps(said).encode()
                self._send(200, body, {"Content-Type": "application/json", "Cache-Control": "no-store"})

            def _render(self, path, query):
                """SimplePdf.respond, to the letter in what it answers if not in how it draws."""
                simple = state.simple
                nothing = (404, b"", {"Content-Type": "text/plain"})
                if simple is None:
                    return self._send(*nothing)

                def number(name):
                    value = query.get(name, "")
                    return int(value) if value.isdigit() and len(value) <= 9 else None

                if path == "/render/document":
                    if simple.error:
                        return self._json({"error": simple.error})
                    width, height = simple.size(1)
                    return self._json({"pages": simple.count, "width": width, "height": height})
                if path == "/render/sizes":
                    first, last = number("from"), number("to")
                    if first is None or last is None or first < 1 or last < first \
                            or last > simple.count or last - first >= 16:
                        return self._send(*nothing)
                    if simple.gate is not None:
                        simple.gate.wait(15)
                    return self._json({"sizes": [list(simple.size(n)) for n in range(first, last + 1)]})
                if path != "/render/page":
                    return self._send(*nothing)
                n, width = number("n"), number("w")
                if n is None or width is None or not 1 <= n <= simple.count or n in simple.broken:
                    return self._send(*nothing)
                with simple.lock:
                    simple.drawing += 1
                    simple.most = max(simple.most, simple.drawing)
                try:
                    time.sleep(simple.delay)
                    from PIL import Image, ImageDraw
                    pw, ph = simple.size(n)
                    width = min(width, 8192)
                    height = max(1, round(width * ph / pw))
                    night = query.get("night") == "1"
                    image = Image.new("RGB", (width, height), (0, 0, 0) if night else (255, 255, 255))
                    band = Simple.colour(n)
                    if night:
                        band = tuple(255 - c for c in band)
                    ImageDraw.Draw(image).rectangle([0, 0, width, max(1, height // 20)], fill=band)
                    out = io.BytesIO()
                    image.save(out, "PNG")
                finally:
                    with simple.lock:
                        simple.drawing -= 1
                self._send(200, out.getvalue(), {
                    "Content-Type": "image/png",
                    "Cache-Control": "no-store",
                    "X-Content-Type-Options": "nosniff",
                })

            def _document(self):
                if state.status_override:
                    return self._send(
                        state.status_override, b"", {"Content-Type": "text/plain"}
                    )
                if state.path is None or not state.path.is_file():
                    return self._send(404, b"no document", {"Content-Type": "text/plain"})

                data = state.path.read_bytes()
                total = len(data)
                ctype = CONTENT_TYPES.get(state.path.suffix, "application/octet-stream")
                header = self.headers.get("Range")
                span = parse_range(header, total) if (state.ranged and header) else None

                if span is None:
                    headers = {"Content-Type": ctype}
                    if state.ranged:
                        headers["Accept-Ranges"] = "bytes"
                    if state.encoding and self.record["query"].get("encoding") == "guess":
                        headers["X-Gander-Encoding"] = state.encoding
                    return self._send(200, data, headers)

                start, end = span
                self._send(206, data[start:end + 1], {
                    "Content-Type": ctype,
                    "Accept-Ranges": "bytes",
                    "Content-Range": f"bytes {start}-{end}/{total}",
                })

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def origin(self):
        return f"http://127.0.0.1:{self.port}"

    def show(self, fixture, ranged=False, status=0, encoding=None):
        """
        Points /doc at a fixture, says whether ranges are on offer, and what code page Gander
        would guess it is in when the page asks.
        """
        self.state.path = fixture if isinstance(fixture, Path) else FIXTURES / fixture
        self.state.ranged = ranged
        self.state.status_override = status
        self.state.encoding = encoding
        self.state.simple = None
        self.state.requests.clear()

    def show_simple(self, sizes=None, pages=0, error=None, broken=(), delay=0.0):
        """
        Has /render/ answer as the simple view's renderer would for a document of pages [sizes]
        in points, or with [error] in place of one, and /doc/ answer nothing, as ViewerActivity
        answers that page.
        """
        self.state.path = None
        self.state.ranged = False
        self.state.status_override = 0
        self.state.simple = Simple(
            sizes=list(sizes or []), pages=pages, error=error, broken=set(broken), delay=delay)
        self.state.requests.clear()
        return self.state.simple

    def pages_asked(self):
        """Each picture of a page asked for, in order: (page, night)."""
        return [(int(r["query"].get("n", 0)), r["query"].get("night") == "1")
                for r in self.state.requests if r["path"] == "/render/page"]

    def url(self, page, name=None, ext=None, **params):
        """
        The URL ViewerActivity would load for this page.

        The length goes on it as ViewerActivity puts it there, whenever the file has one
        and it is not 0. Pass length=None for a file whose provider would not say, as one
        read out of a zip through a pipe will not.
        """
        from urllib.parse import quote
        if ext is None and self.state.path is not None:
            ext = self.state.path.suffix.lstrip(".")
        if name is None and self.state.path is not None:
            name = self.state.path.name
        query = [f"name={quote(name or 'file')}", f"ext={quote(ext or '')}"]
        query.append(f"ranged={1 if self.state.ranged else 0}")
        path = self.state.path
        if "length" not in params and path is not None and path.is_file() and path.stat().st_size:
            query.append(f"length={path.stat().st_size}")
        query += [f"{k}={quote(str(v))}" for k, v in params.items() if v is not None]
        return f"{self.origin}/assets/viewer/{page}?" + "&".join(query)

    def ranged_requests(self):
        return [r for r in self.state.requests
                if r["path"].startswith("/doc/") and r["range"]]

    def full_requests(self):
        return [r for r in self.state.requests
                if r["path"].startswith("/doc/") and not r["range"]]

    def served(self, fragment):
        """Requests whose path contains [fragment] and which were answered."""
        return [r for r in self.state.requests
                if fragment in r["path"] and r["status"] == 200]

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()
