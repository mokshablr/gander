"""Fixtures shared by every viewer test."""

import re
import sys
import zipfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

from port import Port          # noqa: E402
from server import FIXTURES, ViewerServer  # noqa: E402


def pytest_addoption(parser):
    parser.addoption(
        "--update-goldens",
        action="store_true",
        default=False,
        help="Rewrite the reference screenshots instead of comparing against them.",
    )


def pytest_configure(config):
    config.addinivalue_line(
        "markers",
        "visual: compares against a reference screenshot; Linux only, because "
        "text rasterises differently on macOS.",
    )
    config.addinivalue_line(
        "markers",
        "refusals_expected: the test provokes the page's policy on purpose, so the "
        "check that nothing was refused is left to the test.",
    )


# Every page runs under the Content Security Policy in ViewerPolicy.kt. When a renderer
# needs something the policy forbids, nothing fails loudly: the browser refuses it,
# reports it to this event, and the page carries on without it, a picture short or the
# pdf.js worker gone. So every test that drives a page also fails if anything at all was
# refused, which makes the whole suite the check that the policy and the renderers agree.
POLICY_WATCH = """
window.__vwRefused = [];
document.addEventListener("securitypolicyviolation", function (e) {
  window.__vwRefused.push(e.effectiveDirective + " " + e.blockedURI);
});
"""


@pytest.fixture(autouse=True)
def nothing_refused(request):
    if "page" not in request.fixturenames:
        yield
        return
    page = request.getfixturevalue("page")
    page.add_init_script(POLICY_WATCH)
    yield
    if request.node.get_closest_marker("refusals_expected"):
        return
    try:
        refused = page.evaluate("window.__vwRefused || []")
    except Exception:
        # The page is gone or mid-navigation; the test has already said what it saw.
        return
    assert refused == [], f"the page's policy refused {refused}"


@pytest.fixture(scope="session")
def server():
    """One server for the session; each test points it at its own document."""
    s = ViewerServer()
    yield s
    s.stop()


@pytest.fixture
def viewer(page, server):
    """
    Opens a viewer page the way ViewerActivity would, and answers the page.

        viewer("pdf.html", "six-pages.pdf")
    """
    def open_page(html, fixture=None, ranged=False, status=0, wait=True, encoding=None, **params):
        if fixture is not None:
            server.show(fixture, ranged=ranged, status=status, encoding=encoding)
        page.goto(server.url(html, **params))
        if wait:
            page.wait_for_load_state("domcontentloaded")
        return page

    return open_page


@pytest.fixture
def port(page):
    """The message channel, handed to the page after it has loaded."""
    def attach():
        return Port(page)
    return attach


@pytest.fixture
def made(tmp_path):
    """
    A file written for one test, for the sizes too large to commit.

        made("big.txt", b"x" * 6_000_000)
    """
    def write(name, data):
        target = tmp_path / name
        target.write_bytes(data if isinstance(data, bytes) else data.encode())
        return target
    return write


@pytest.fixture
def fixture_path():
    return lambda name: FIXTURES / name


@pytest.fixture
def main_part():
    """
    What an Office fixture's [Content_Types].xml declares its main part to be.

    That one line is all that tells a template, a slide show or a macro-enabled
    file from its format, so a test of one reads it off the file first rather
    than trusting the name, and cannot quietly be testing a .docx renamed.

        main_part("report.docm")
    """
    def read(name):
        with zipfile.ZipFile(FIXTURES / name) as z:
            types = z.read("[Content_Types].xml").decode("utf-8")
        return re.findall(r'ContentType="([^"]+\.main\+xml)"', types)
    return read


@pytest.fixture(scope="session")
def big_pdf(tmp_path_factory):
    """
    A document large enough that pdf.js genuinely fetches it in pieces.

    Built rather than committed: it is a few megabytes, and the point of it is
    only its size. Ranged loading starts at 16 MB in the app, but what is being
    tested here is that pdf.js asks for ranges at all, which it does as soon as
    the file is big enough not to be worth reading whole.
    """
    from reportlab.lib.pagesizes import A4
    from reportlab.pdfgen import canvas

    target = tmp_path_factory.mktemp("big") / "many-pages.pdf"
    c = canvas.Canvas(str(target), pagesize=A4, invariant=1)
    for n in range(1, 301):
        c.setFont("Helvetica-Bold", 16)
        c.drawString(72, 760, f"Section {n}")
        c.setFont("Helvetica", 10)
        for line in range(40):
            c.drawString(72, 720 - line * 16, f"Page {n}, line {line}, padding to give the file some size.")
        c.showPage()
    c.save()
    return target


@pytest.fixture(scope="session")
def browser_context_args(browser_context_args):
    # ViewerActivity lays the pages out at 980 CSS px and lets the WebView zoom
    # them to fit, so the viewport is fixed here rather than left to Playwright.
    return {**browser_context_args, "viewport": {"width": 980, "height": 1600}}
