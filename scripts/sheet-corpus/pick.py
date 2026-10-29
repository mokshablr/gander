"""The workbooks in a folder worth scoring, one name a line: each .xlsx or .xlsm whose styles have a
fill or a font that is not plain, and each .xls with a style record that has a fill."""
import os
import re
import struct
import sys
import zipfile


def xls_has_fill(data):
    """
    Whether an XF record has a pattern, found by its header in the file's bytes: a compound file
    keeps a small workbook's stream in order, and a header cut by a sector is only a file missed.
    """
    at = data.find(b"\xe0\x00\x14\x00")
    while at >= 0:
        if at + 22 <= len(data) and 0 < struct.unpack_from("<I", data, at + 18)[0] >> 26 <= 18:
            return True
        at = data.find(b"\xe0\x00\x14\x00", at + 1)
    return False


root = sys.argv[1]
FILL = re.compile(r'patternType="(?!none")(?!gray125")\w+"|gradientFill')
FONT = re.compile(r'<(?:\w+:)?(b|i|u|strike)\b(?![^>]*val="(0|false|none)")'
                  r'|<(?:\w+:)?color\b[^>]*(rgb="(?!FF000000)|indexed="(?!(8|64)")|theme="(?!1")\d)')
for name in sorted(os.listdir(root)):
    low = name.lower()
    if low.endswith(".xls"):
        with open(os.path.join(root, name), "rb") as fh:
            if xls_has_fill(fh.read()):
                print(name)
        continue
    if not low.endswith((".xlsx", ".xlsm")):
        continue
    try:
        z = zipfile.ZipFile(os.path.join(root, name))
        part = next((n for n in z.namelist() if re.search(r"(^|/)styles\.xml$", n, re.I)), None)
        styles = z.read(part).decode("utf-8", "replace") if part else ""
    except Exception:
        continue
    fills = re.search(r"<(?:\w+:)?fills\b.*?</(?:\w+:)?fills>", styles, re.S)
    fonts = re.search(r"<(?:\w+:)?fonts\b.*?</(?:\w+:)?fonts>", styles, re.S)
    if (fills and FILL.search(fills.group(0))) or (fonts and FONT.search(fonts.group(0))):
        print(name)
