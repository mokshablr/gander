#!/bin/bash
# LibreOffice's HTML export of every workbook in <dir>/files that colours its cells, into
# <dir>/refs/<extension>, and their names into <dir>/picked.txt: see pick.py. LibreOffice names
# what it exports after the file less its extension, so each extension has a folder of its own,
# for budget.xls and budget.xlsx not to share one. LibreOffice's own profile is kept apart from yours.
#
#   scripts/sheet-corpus/refs.sh /tmp/sheet-corpus
set -euo pipefail
W=${1:?usage: refs.sh <dir>}
HERE=$(cd "$(dirname "$0")" && pwd)
python3 -I "$HERE/pick.py" "$W/files" > "$W/picked.txt"
cd "$W/files"
# Twenty files to a run of LibreOffice, each run given five minutes; a file already exported is
# left as it is
split -l 20 "$W/picked.txt" "$W/batch-"
for b in "$W"/batch-*; do
  for ext in xlsx xlsm xls; do
    files=()
    while read -r f; do
      e=$(printf '%s' "${f##*.}" | tr 'A-Z' 'a-z')
      if [ "$e" = "$ext" ] && [ ! -f "$W/refs/$ext/${f%.*}.html" ]; then files+=("$f"); fi
    done < "$b"
    if [ ${#files[@]} -gt 0 ]; then
      mkdir -p "$W/refs/$ext"
      perl -e 'alarm shift; exec @ARGV' 300 soffice -env:UserInstallation="file://$W/lo-profile" \
        --headless --convert-to html --outdir "$W/refs/$ext" "${files[@]}" > /dev/null 2>&1 || true
    fi
  done
  rm -f "$b"
done
echo "$(wc -l < "$W/picked.txt") picked, $(find "$W/refs" -name '*.html' | wc -l) exported"
