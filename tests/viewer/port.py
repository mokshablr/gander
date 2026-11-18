"""
The message channel ViewerActivity opens into pdf.html.

openSearchChannel creates a MessageChannel, keeps port1 and posts port2 into
the page as "vw-search-port". The page takes e.ports[0] and speaks the
protocol in PortMessage.kt over it. This does the Kotlin half from the test,
so what the page emits is checked against the same shapes the app parses.
"""

import re
import time

_HANDSHAKE = """
() => {
  const channel = new MessageChannel();
  window.__vwInbox = [];
  window.__vwPort = channel.port1;
  channel.port1.onmessage = (e) => window.__vwInbox.push(String(e.data));
  channel.port1.start();
  window.postMessage("vw-search-port", "*", [channel.port2]);
}
"""


class Port:
    """One end of the channel, from the outside."""

    def __init__(self, page):
        self.page = page
        page.evaluate(_HANDSHAKE)

    def send(self, command):
        self.page.evaluate("(c) => window.__vwPort.postMessage(c)", command)

    # The commands PortCommand.kt builds, but for the title bar's two
    def query(self, q):
        self.send(f"q{q}")

    def next(self):
        self.send("n")

    def prev(self):
        self.send("p")

    def clear(self):
        self.send("c")

    def go_to_page(self, n):
        self.send(f"g{n}")

    def night_mode(self, on):
        self.send("i1" if on else "i0")

    def entry(self, n):
        """An entry of the contents list the page sent, by its number in it."""
        self.send(f"e{n}")

    def close_over(self):
        self.send("x")

    def messages(self):
        return self.page.evaluate("() => window.__vwInbox.slice()")

    def counts(self):
        """Search counters only: "<at> <total> <done>"."""
        return [m for m in self.messages() if re.fullmatch(r"\d+ \d+ [01]", m)]

    def pages(self):
        """Page reports only: "page <n> <of>"."""
        return [m for m in self.messages() if m.startswith("page ")]

    def clear_inbox(self):
        self.page.evaluate("() => { window.__vwInbox.length = 0; }")

    def wait_for(self, pattern, timeout=15.0):
        """Waits for a message matching [pattern], and answers it."""
        deadline = time.time() + timeout
        seen = []
        while time.time() < deadline:
            seen = self.messages()
            for message in seen:
                if re.fullmatch(pattern, message):
                    return message
            self.page.wait_for_timeout(50)
        raise AssertionError(
            f"No message matching {pattern!r} within {timeout}s. Saw: {seen}"
        )

    def wait_for_count(self, at, total, done=True, timeout=15.0):
        return self.wait_for(rf"{at} {total} {1 if done else 0}", timeout)

    def last_count(self, timeout=15.0):
        """The final search counter, once it has stopped changing."""
        deadline = time.time() + timeout
        previous, stable_since = None, time.time()
        while time.time() < deadline:
            counts = self.counts()
            current = counts[-1] if counts else None
            if current != previous:
                previous, stable_since = current, time.time()
            elif current is not None and time.time() - stable_since > 0.35:
                return current
            self.page.wait_for_timeout(50)
        return previous
