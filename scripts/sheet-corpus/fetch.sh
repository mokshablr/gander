#!/bin/bash
# Apache POI's spreadsheet test files under 400 KB, into <dir>/files: real workbooks from Excel
# and from many other programs, about 760 of them and 24 MB.
#
#   scripts/sheet-corpus/fetch.sh /tmp/sheet-corpus
set -euo pipefail
W=${1:?usage: fetch.sh <dir>}
mkdir -p "$W/files"
gh api "repos/apache/poi/git/trees/trunk?recursive=1" \
  --jq '.tree[] | select(.type=="blob") | select(.path|startswith("test-data/spreadsheet/"))
        | select(.path|test("\\.(xlsx|xlsm|xls)$")) | select(.size < 400000) | .path' \
  | sed 's|^|https://raw.githubusercontent.com/apache/poi/trunk/|' > "$W/urls.txt"
cd "$W/files" && xargs -P 12 -n 1 curl -sSfLO < "$W/urls.txt" || true
echo "$(ls "$W/files" | wc -l) files in $W/files"
