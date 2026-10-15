#!/usr/bin/env python3
"""Writes the small Dutch-English StarDict dictionary the emulator test looks words up in.

Usage: make_dictionary.py <outdir>
Output: <outdir>/smoke-nl-en.zip, the form the app takes StarDict dictionaries in.
"""
import os
import struct
import sys
import zipfile

BOOKNAME = "Smoke NL-EN"
BASE = "smoke-nl-en"

# Keys are not prefixes of one another, so a lookup opens exactly one article
ENTRIES = {
    "fiets": "<p><b>fiets</b> <i>de (m.), fietsen</i></p><p>bicycle</p>",
    "gezellig": "<p><b>gezellig</b> <i>bijv. nw.</i></p><p>cosy, sociable, pleasant "
                "(of company or an atmosphere); has no single English equivalent</p>",
    "huis": "<p><b>huis</b> <i>het, huizen</i></p><p>house, home</p>",
    "lopen": "<p><b>lopen</b> <i>ww., liep, gelopen</i></p><p>1. to walk<br/>2. to run "
             "(of a machine, a nose, a contract)</p>",
    "uitwaaien": "<p><b>uitwaaien</b> <i>ww.</i></p><p>to go out in windy weather to "
                 "clear one's head</p>",
    "zuinig": "<p><b>zuinig</b> <i>bijv. nw.</i></p><p>thrifty, economical</p>",
}


def main():
    outdir = sys.argv[1]
    os.makedirs(outdir, exist_ok=True)
    entries = []
    dict_blob = bytearray()
    for key, html in ENTRIES.items():
        body = html.encode("utf-8")
        entries.append((key, len(dict_blob), len(body)))
        dict_blob += body
    # .idx: null-terminated UTF-8 key + 4-byte BE offset + 4-byte BE size, sorted by key
    idx = bytearray()
    for key, offset, size in sorted(entries, key=lambda entry: entry[0].encode("utf-8")):
        idx += key.encode("utf-8") + b"\x00" + struct.pack(">II", offset, size)
    ifo = (
        "StarDict's dict ifo file\n"
        "version=2.4.2\n"
        "bookname=%s\n"
        "wordcount=%d\n"
        "idxfilesize=%d\n"
        "sametypesequence=h\n" % (BOOKNAME, len(entries), len(idx))
    )
    archive_path = os.path.join(outdir, BASE + ".zip")
    with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(BASE + ".ifo", ifo)
        archive.writestr(BASE + ".idx", bytes(idx))
        archive.writestr(BASE + ".dict", bytes(dict_blob))
    print(archive_path)


if __name__ == "__main__":
    main()
