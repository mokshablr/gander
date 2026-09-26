"""
The harness answers ranges the way the app does.

server.py stands in for ViewerActivity.docResponse, and a stand-in that
behaves differently from the thing it replaces makes every test above it a
test of the wrong software. Both parsers read the same table of cases:
RangeParityTest for the Kotlin one, this for the Python one.
"""

import json
from pathlib import Path

import pytest

from server import parse_range

CASES = json.loads(
    (Path(__file__).resolve().parents[1] / "fixtures/range-cases.json").read_text()
)["cases"]


@pytest.mark.parametrize(
    "case", CASES, ids=[f"{c['header']!r}of{c['total']}" for c in CASES]
)
def test_the_harness_parses_a_range_the_way_the_app_does(case):
    expected = tuple(case["expected"]) if case["expected"] else None
    assert parse_range(case["header"], case["total"]) == expected


def test_the_table_covers_both_answers():
    """A table of only-nulls would pass against a parser that always says no."""
    assert len(CASES) >= 15
    assert any(c["expected"] for c in CASES)
    assert any(c["expected"] is None for c in CASES)
