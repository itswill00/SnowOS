#!/bin/bash
# apply.sh INITRD.gz: put this folder's scripts into a helper initramfs in place.
# pull-setup.sh copies the PC installer's initrd.gz, which carries the jobs it
# was built with; the phone's fork carries its own tuned jobs here and applies
# them after the copy, so a pull never loses them. The initrd is a gzip'd newc
# cpio; the files are replaced by name, everything else stays byte for byte.
set -euo pipefail
d=$(mktemp -d)
trap 'rm -rf "$d"' EXIT
gzip -dc "$1" | (cd "$d" && cpio -idm --quiet)
for f in "$@"; do
    [ -f "$f" ] || { echo "no $f" >&2; exit 1; }
    install -m 755 "$f" "$d/asoft/$(basename "$f")"
done
(cd "$d" && find . -print0 | LC_ALL=C sort -z | cpio -0 -o -H newc --owner=0:0 --quiet | gzip -6) > "$1"
