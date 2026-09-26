"""prose.html's Word 97-2003 reader, on files written for one test each.

legacy.doc, which test_prose.py reads beside the other two formats, is one short
document, and each bug pinned here needs a file of another shape. Word97 below writes
them at test time, by hand from MS-DOC and MS-CFB the way make_fixtures.py writes
legacy.doc, so that none has to be committed. Their words are invented, as every
fixture's are.
"""

import struct
import time

import pytest

from server import FIXTURES


def u16(v): return struct.pack("<H", v)
def i16(v): return struct.pack("<h", v)
def u32(v): return struct.pack("<I", v)
def i32(v): return struct.pack("<i", v)


def sprm(code, operand=b"\x01"):
    """A sprm: a two-byte code, which says how long its operand is, then the operand."""
    return u16(code) + operand


# The sprms these files use, by their names in MS-DOC
BOLD = sprm(0x0835)             # sprmCFBold
SPECIAL = sprm(0x0855)          # sprmCFSpec: the character is an anchor, a picture or a note mark
IN_TABLE = sprm(0x2416)         # sprmPFInTable
ROW_END = sprm(0x2417)          # sprmPFTtp: the mark that ends a table row

# ---------------------------------------------------------------------------
# The compound file
# ---------------------------------------------------------------------------

END, FREE, FATSECT, NOSTREAM = 0xFFFFFFFE, 0xFFFFFFFF, 0xFFFFFFFD, 0xFFFFFFFF


def cfb_header(shift, fat_count, first_dir, fat_sectors, first_difat=END, difat_count=0):
    h = bytearray(512)
    h[0:8] = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"
    h[0x18:0x1A] = u16(0x3E)                         # minor version
    h[0x1A:0x1C] = u16(3 if shift == 9 else 4)       # major version, which sets the sector size
    h[0x1C:0x1E] = u16(0xFFFE)                       # byte order
    h[0x1E:0x20] = u16(shift)
    h[0x20:0x22] = u16(6)                            # 64-byte mini sectors
    h[0x2C:0x30] = u32(fat_count)
    h[0x30:0x34] = u32(first_dir)
    h[0x38:0x3C] = u32(4096)                         # streams this short live in the mini stream
    h[0x3C:0x40] = u32(END)                          # there is no mini FAT
    h[0x44:0x48] = u32(first_difat)
    h[0x48:0x4C] = u32(difat_count)
    for i in range(109):
        h[0x4C + 4 * i:0x50 + 4 * i] = u32(fat_sectors[i] if i < len(fat_sectors) else FREE)
    return h


def dir_entry(name, kind, start=END, size=0, child=NOSTREAM, right=NOSTREAM):
    e = bytearray(128)
    if kind:
        raw = name.encode("utf-16-le") + b"\0\0"
        e[0:len(raw)] = raw
        e[64:66] = u16(len(raw))
        e[66] = kind
        e[67] = 1                                    # black
    e[68:80] = u32(NOSTREAM) + u32(right) + u32(child)
    e[116:120] = u32(start)
    e[120:124] = u32(size)
    return bytes(e)


def compound(streams):
    """
    A compound file around streams, in 512-byte sectors: the FAT, the directory, then
    each stream in a chain of its own. A stream shorter than 4,096 bytes is padded to
    that, so that none belongs in the mini stream, which this does not write.
    """
    blobs = [(name, data + bytes(max(0, 4096 - len(data)))) for name, data in streams.items()]
    counts = [-(-128 * (len(blobs) + 1) // 512)] + [-(-len(data) // 512) for _, data in blobs]
    fat_count = 1
    while 128 * fat_count < fat_count + sum(counts):
        fat_count += 1
    assert fat_count <= 109, "more FAT than the header can list"
    fat = [FATSECT] * fat_count + [FREE] * (127 * fat_count)
    starts, at = [], fat_count
    for count in counts:
        starts.append(at)
        fat[at:at + count] = list(range(at + 1, at + count)) + [END]
        at += count

    directory = dir_entry("Root Entry", 5, child=1)
    for i, (name, data) in enumerate(blobs):
        last = i + 1 == len(blobs)
        directory += dir_entry(name, 2, starts[i + 1], len(data), right=NOSTREAM if last else i + 2)
    directory += dir_entry("", 0) * ((512 * counts[0] - len(directory)) // 128)
    body = b"".join(data + bytes(-len(data) % 512) for _, data in blobs)
    header = cfb_header(9, fat_count, starts[0], list(range(fat_count)))
    return bytes(header) + b"".join(u32(v) for v in fat) + directory + body


# ---------------------------------------------------------------------------
# The document
# ---------------------------------------------------------------------------

# A stylesheet with no styles, and a font table with one font, as legacy.doc has
EMPTY_STSH = u16(18) + u16(0) + u16(10) + u16(0) * 4 + u16(0) * 3
_NAME = "Times New Roman".encode("utf-16-le") + b"\0\0"
_FFN = bytes([40 + len(_NAME) - 1, 0x12]) + u16(400) + bytes(36) + _NAME
ONE_FONT = u16(1) + u16(0) + _FFN


def papx_in_fkp(grpprl):
    """A paragraph's istd and sprms as a formatting page holds them, counted in words."""
    if len(grpprl) % 2:
        return bytes([(len(grpprl) + 1) // 2]) + grpprl
    return bytes([0, len(grpprl) // 2]) + grpprl


def fkp_page(runs, index_size, encode):
    page = bytearray(512)
    n = len(runs)
    for i, (fc, _, _) in enumerate(runs):
        page[4 * i:4 * i + 4] = u32(fc)
    page[4 * n:4 * n + 4] = u32(runs[-1][1])
    top, placed = 511, {}
    for i, (_, _, props) in enumerate(runs):
        if not props:
            continue
        if props not in placed:
            blob = encode(props)
            top = (top - len(blob)) & ~1
            page[top:top + len(blob)] = blob
            placed[props] = top
        page[4 * (n + 1) + index_size * i] = placed[props] // 2
    assert top >= 4 * (n + 1) + index_size * n
    page[511] = n
    return bytes(page)


def fkp(runs, index_size, encode):
    """
    Runs of (fc, fc_end, properties) in 512-byte formatting pages, as many to a page as
    fit: the FCs, an entry of index_size bytes per run whose first byte says where its
    properties are, in words, and the properties packed down from the end, shared by
    the runs that have the same. Answers [(first fc, page)].
    """
    pages, start = [], 0
    while start < len(runs):
        end, used, seen = start, 0, set()
        while end < len(runs):
            props = runs[end][2]
            more = len(encode(props)) + 1 if props and props not in seen else 0
            if 4 * (end - start + 2) + index_size * (end - start + 1) + used + more > 511:
                break
            used += more
            seen.add(props)
            end += 1
        assert end > start, "a run's properties do not fit a page"
        pages.append((runs[start][0], fkp_page(runs[start:end], index_size, encode)))
        start = end
    return pages


def bin_table(pages, first, fc_end):
    """PlcBteChpx or PlcBtePapx: the FC each page starts at, the end, the page numbers."""
    return (b"".join(u32(fc) for fc, _ in pages) + u32(fc_end) +
            b"".join(u32(first + i) for i in range(len(pages))))


def fib(fc_text, fc_end, ccp_text, cb_mac, places):
    """A Word 97 FIB whose table stream is 1Table, with each structure's place in it."""
    f = bytearray(0x400)
    f[0x00:0x02] = u16(0xA5EC)                       # wIdent
    f[0x02:0x04] = u16(0xC1)                         # nFib: Word 97
    f[0x06:0x08] = u16(0x0409)                       # lid
    f[0x0A:0x0C] = u16(0x0200)                       # fWhichTblStm: 1Table
    f[0x0C:0x0E] = u16(0xBF)                         # nFibBack
    f[0x18:0x1C] = u32(fc_text)
    f[0x1C:0x20] = u32(fc_end)
    f[0x20:0x22] = u16(14)                           # csw
    f[0x3E:0x40] = u16(22)                           # cslw
    f[0x40:0x44] = u32(cb_mac)
    f[0x4C:0x50] = u32(ccp_text)
    f[0x98:0x9A] = u16(0x5D)                         # cbRgFcLcb: Word 97's 93 pairs
    for index, (fc, lcb) in places.items():
        f[0x9A + 8 * index:0xA2 + 8 * index] = u32(fc) + u32(lcb)
    return f


class Word97:
    """
    A Word 97 document: its text in UTF-16 as one piece, the character and paragraph
    formatting in pages after it, and whatever else a test puts in the table stream,
    keyed by its place in the FIB's FibRgFcLcb97.
    """

    def __init__(self):
        self.paragraphs = []            # (istd, paragraph sprms, [(text, character sprms)])
        self.ahead = bytearray()        # WordDocument bytes between the FIB and the text
        self.table = {1: EMPTY_STSH, 15: ONE_FONT}
        self.data = None

    def add(self, *runs, pap=b"", istd=0):
        """A paragraph of runs, each text or (text, character sprms), ending in its mark."""
        runs = [(run, b"") if isinstance(run, str) else run for run in runs]
        assert runs[-1][0][-1] in "\r\x07\x0c"
        self.paragraphs.append((istd, pap, runs))

    @property
    def cp(self):
        """The CP the next paragraph will start at."""
        return sum(len(text) for _, _, runs in self.paragraphs for text, _ in runs)

    def ahead_of_text(self, blob):
        """Puts bytes into WordDocument after the FIB, and answers their offset."""
        self.ahead += bytes(len(self.ahead) % 2)
        at = 0x400 + len(self.ahead)
        self.ahead += blob
        return at

    def build(self):
        text = "".join(t for _, _, runs in self.paragraphs for t, _ in runs)
        fc_text = 0x400 + len(self.ahead)
        fc_text += -fc_text % 16
        fc_end = fc_text + 2 * len(text)

        chp_runs, pap_runs, fc = [], [], fc_text
        for istd, pap, runs in self.paragraphs:
            start = fc
            for t, chp in runs:
                chp_runs.append((fc, fc + 2 * len(t), chp))
                fc += 2 * len(t)
            pap_runs.append((start, fc, u16(istd) + pap))
        chp_pages = fkp(chp_runs, 1, lambda grpprl: bytes([len(grpprl)]) + grpprl)
        pap_pages = fkp(pap_runs, 13, papx_in_fkp)

        first = -(-fc_end // 512)
        word = bytearray(512 * first)
        word[0x400:0x400 + len(self.ahead)] = self.ahead
        word[fc_text:fc_end] = text.encode("utf-16-le")
        for _, page in chp_pages + pap_pages:
            word += page

        parts = dict(self.table)
        parts[12] = bin_table(chp_pages, first, fc_end)
        parts[13] = bin_table(pap_pages, first + len(chp_pages), fc_end)
        parts[33] = bytes([2]) + u32(16) + u32(0) + u32(len(text)) + u16(0) + u32(fc_text) + u16(0)
        table, places = bytearray(), {}
        for index in sorted(parts):
            blob, lcb = parts[index] if isinstance(parts[index], tuple) else (parts[index], len(parts[index]))
            table += bytes(-len(table) % 16)
            places[index] = (len(table), lcb)
            table += blob

        word[0:0x400] = fib(fc_text, fc_end, len(text), len(word), places)
        streams = {"WordDocument": bytes(word), "1Table": bytes(table)}
        if self.data is not None:
            streams["Data"] = self.data
        return compound(streams)


def record(ver, inst, kind, body):
    """An Office Art record: its version and instance in a word, its type, its length, its body."""
    return u16(ver | inst << 4) + u16(kind) + u32(len(body)) + body


def float_picture(doc, anchor, png, width, height):
    """
    Floats a PNG width by height twips at the anchor character at CP anchor, where Word
    keeps one: the picture in WordDocument, a blip store entry pointing at it in the
    drawing group, and a shape that shows it in the main text's drawing, placed by
    PlcSpaMom. The Office Art content is the group's container, then for each drawing a
    byte that says whose it is and the drawing's container.
    """
    uid = bytes(range(16))
    blip = record(0, 0x6E0, 0xF01E, uid + b"\xff" + png)
    at = doc.ahead_of_text(blip)
    entry = record(2, 6, 0xF007, bytes([6, 6]) + uid + u16(0xFF) + u32(len(blip)) + u32(1) + u32(at) + bytes(4))
    group = record(15, 0, 0xF000,
                   record(0, 0, 0xF006, u32(1026) + u32(2) + u32(2) + u32(1) + u32(1) + u32(2)) +
                   record(15, 1, 0xF001, entry))
    patriarch = record(15, 0, 0xF004, record(1, 0, 0xF009, bytes(16)) + record(2, 0, 0xF00A, u32(1024) + u32(5)))
    picture = record(15, 0, 0xF004, record(2, 75, 0xF00A, u32(1025) + u32(0xA00)) +
                     record(3, 1, 0xF00B, u16(0x4104) + u32(1)))
    drawing = record(15, 0, 0xF002, record(0, 1, 0xF008, u32(2) + u32(1025)) +
                     record(15, 0, 0xF003, patriarch + picture))
    doc.table[50] = group + b"\x00" + drawing
    doc.table[40] = (u32(anchor) + u32(anchor + 1) +
                     u32(1025) + i32(0) + i32(0) + i32(width) + i32(height) + u16(0) + u32(0))


# ---------------------------------------------------------------------------
# The tests
# ---------------------------------------------------------------------------

def wait_for_text(page, words, timeout=20000):
    page.wait_for_function(
        "(words) => document.querySelector('.vw-paper > section') && "
        "document.querySelector('#container').textContent.includes(words)",
        arg=words, timeout=timeout,
    )


def test_a_floating_picture_is_drawn_from_the_drawing(viewer, page, made):
    """
    A floating picture is not in the text: its anchor character points into the drawing,
    whose shape names a blip store entry, whose picture Word keeps in WordDocument. Read
    as one run of records, the byte ahead of the drawing began a record and hid it, and
    the picture was looked for in the Data stream, so none was ever drawn.
    """
    doc = Word97()
    before = "A picture floats beside this paragraph. "
    doc.add(before, ("\x08", SPECIAL), "The words go on after its anchor.\r")
    float_picture(doc, len(before), (FIXTURES / "tiny.png").read_bytes(), 2880, 1440)
    viewer("prose.html", made("floating.doc", doc.build()))
    wait_for_text(page, "after its anchor")
    page.wait_for_function(
        "() => { const i = document.querySelectorAll('.vw-paper img');"
        "return i.length === 1 && i[0].complete && i[0].naturalWidth > 0; }",
        timeout=10000,
    )
    assert page.evaluate("() => document.querySelector('.vw-paper img').style.width") == "144pt"


def test_a_long_document_is_read_in_time_that_grows_with_its_length(viewer, page, made):
    """
    Twelve thousand paragraphs in one piece, as a file Word saved whole keeps them. The
    formatting of each paragraph was found by reading all of the piece's formatting
    pages again, so the time went as the square of the length: this file took twenty
    seconds, and takes well under one. The last paragraph's bold words, furthest into
    the pages, must still be bold.

    Measured against a shorter copy of itself rather than against a clock, so the
    answer is the same on a laptop and on a loaded runner. Six times the paragraphs
    is under six times the time when the time grows with the length, and less once
    the page's own start-up is counted in both; read the old way it is thirty-six.
    """
    def opened(paragraphs):
        doc = Word97()
        for n in range(1, paragraphs + 1):
            doc.add(f"Paragraph {n} of the long report, ", ("with a bold phrase", BOLD), " and plain words after it.\r")
        data = doc.build()
        started = time.monotonic()
        viewer("prose.html", made(f"long-{paragraphs}.doc", data))
        wait_for_text(page, f"Paragraph {paragraphs} of the long report", timeout=60000)
        return time.monotonic() - started

    short = opened(2000)
    long = opened(12000)
    assert long < 12 * short, \
        f"twelve thousand paragraphs took {long:.2f} s against {short:.2f} s for two thousand"
    last = page.evaluate(
        "() => { const ps = document.querySelectorAll('.vw-paper p');"
        "  const out = { count: ps.length };"
        "  for (const el of ps[ps.length - 1].querySelectorAll('span')) {"
        "    if (el.textContent === 'with a bold phrase') out.bold = getComputedStyle(el).fontWeight;"
        "  } out.plain = getComputedStyle(ps[ps.length - 1]).fontWeight; return out; }"
    )
    assert last == {"count": 12000, "bold": "700", "plain": "400"}


def test_a_row_whose_properties_are_kept_in_the_data_stream_keeps_its_table(viewer, page, made):
    """
    A row's properties outgrow its formatting page when it has many cells or borders,
    and are kept in the Data stream, with sprmPHugePapx pointing at them. MS-DOC numbers
    that sprm 0x6646 and only 0x6645 was followed, so such a row lost its column widths
    and borders, and its end mark became an empty paragraph under the table.
    """
    tc = u16(0) + u16(0) + bytes([8, 1, 1, 0]) * 4      # a one-point black line on each side
    definition = bytes([2]) + i16(0) + i16(2880) + i16(7200) + tc + tc
    row = IN_TABLE + ROW_END + sprm(0xD608, u16(len(definition) + 1) + definition)
    doc = Word97()
    doc.data = bytes(16) + u16(len(row)) + row
    doc.add("Left\x07", pap=IN_TABLE)
    doc.add("Right\x07", pap=IN_TABLE)
    doc.add("\x07", pap=sprm(0x6646, u32(16)))
    doc.add("Under the table.\r")
    viewer("prose.html", made("huge.doc", doc.build()))
    wait_for_text(page, "Under the table")
    drawn = page.evaluate(
        "() => ({"
        "  cols: [...document.querySelectorAll('.vw-paper col')].map(c => c.style.width),"
        "  cells: [...document.querySelectorAll('.vw-paper td')]"
        "    .map(td => [td.textContent.trim(), getComputedStyle(td).borderTopStyle]),"
        "  after: [...document.querySelectorAll('.vw-body > p')].map(p => p.textContent) })"
    )
    assert drawn == {
        "cols": ["144pt", "216pt"],
        "cells": [["Left", "solid"], ["Right", "solid"]],
        "after": ["Under the table."],
    }


def test_properties_in_the_data_stream_that_point_at_themselves_are_not_followed_round(viewer, page, made):
    """A damaged file's sprmPHugePapx can name the very bytes in the Data stream that hold it."""
    doc = Word97()
    loop = sprm(0x6646, u32(16))
    doc.data = bytes(16) + u16(len(loop)) + loop
    doc.add("A paragraph whose properties go round in a circle.\r", pap=loop)
    viewer("prose.html", made("circle.doc", doc.build()))
    wait_for_text(page, "go round in a circle")


def difat_that_names_itself(shift=12):
    """Asks for four thousand million FAT sectors, listed by a DIFAT sector that names itself as the next."""
    header = cfb_header(shift, 0xFFFFFFFF, 0, [0] * 109, first_difat=0, difat_count=1)
    return bytes(header) + bytes((2 << shift) - 512)


def directory_that_names_itself():
    """A directory sector whose FAT entry points back at it, of entries with the longest names allowed."""
    header = cfb_header(12, 1, 0, [1])
    directory = dir_entry("Root Entry", 5) + dir_entry("Thirty-one characters of a name", 2) * 31
    fat = u32(0) + u32(FATSECT) + u32(FREE) * 1022
    return bytes(header) + bytes(4096 - 512) + directory + fat


def root_longer_than_the_file():
    """A document whose streams all belong in the mini stream, which the root entry says is 4 GB."""
    doc = Word97()
    doc.add("A document whose root entry claims more than any phone holds.\r")
    data = bytearray(doc.build())
    root = 512 * (1 + struct.unpack_from("<I", data, 0x2C)[0])
    data[root + 120:root + 124] = u32(0xFFFFFFF0)
    data[0x38:0x3C] = u32(0xFFFFFFFF)
    return bytes(data)


TRAPS = {
    "difat-loop": difat_that_names_itself,
    "sectors-of-64k": lambda: difat_that_names_itself(shift=16),
    "directory-loop": directory_that_names_itself,
    "root-too-long": root_longer_than_the_file,
    "shorter-than-a-sector": lambda: bytes(cfb_header(12, 1, 0, [0])) + bytes(512),
}


@pytest.mark.parametrize("trap", list(TRAPS))
def test_a_compound_file_built_to_trap_its_reader_is_refused_at_once(viewer, page, made, trap):
    """
    The compound file's numbers were believed: sectors of any size to 64 KB, as many FAT
    sectors as the header asked for, chains stopped only by counting steps, and a root
    entry of any length. These four files held the reader for seconds, filled hundreds
    of megabytes with a directory read over and over, or ended in a RangeError. Each is
    refused at once, in the reader's own words, timed in the page as well because the
    directory's cost is in memory and hardly shows on the clock of a whole page load.
    """
    data = TRAPS[trap]()
    viewer("prose.html", made("trap.doc", data))
    page.wait_for_selector(".vw-error", timeout=5000)
    assert "Word document" in page.text_content(".vw-error-detail")
    kind, took = page.evaluate(
        "(b) => { const buffer = Uint8Array.from(b).buffer; const started = performance.now();"
        "  try { vwReadDoc(buffer, document.createElement('div')); }"
        "  catch (e) { return [e.constructor.name, performance.now() - started]; }"
        "  return ['nothing', performance.now() - started]; }",
        list(data),
    )
    assert kind == "Error" and took < 100, f"{kind} after {took:.0f} ms"
