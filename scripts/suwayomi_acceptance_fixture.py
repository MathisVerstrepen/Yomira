#!/usr/bin/env python3
# KMK -->
"""Seed only a fresh disposable Suwayomi NONE-auth instance for opt-in live tests."""

import argparse
import json
import struct
import urllib.request
import zipfile
import zlib
from pathlib import Path


def png(page):
    width, height = 320, 480
    pixels = bytearray([246, 240, 225] * width * height)
    segments = {
        1: [(150, 120, 160, 300)],
        2: [(90, 110, 210, 125), (195, 125, 210, 200), (90, 200, 210, 215), (90, 215, 105, 290), (90, 290, 210, 305)],
        3: [(90, 110, 210, 125), (195, 125, 210, 305), (90, 200, 210, 215), (90, 290, 210, 305)],
        4: [(90, 110, 105, 215), (90, 200, 210, 215), (195, 110, 210, 305)],
        5: [(90, 110, 210, 125), (90, 125, 105, 215), (90, 200, 210, 215), (195, 215, 210, 305), (90, 290, 210, 305)],
        6: [(90, 110, 105, 305), (90, 110, 210, 125), (90, 200, 210, 215), (195, 215, 210, 305), (90, 290, 210, 305)],
    }
    for left, top, right, bottom in segments[page]:
        for y in range(top, bottom):
            for x in range(left, right):
                offset = (y * width + x) * 3
                pixels[offset:offset + 3] = b"\x20\x20\x20"
    rows = b"".join(b"\x00" + pixels[y * width * 3:(y + 1) * width * 3] for y in range(height))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-data-dir", type=Path, required=True)
    parser.add_argument("--url", default="http://127.0.0.1:4579")
    parser.add_argument("--disposable", action="store_true", required=True, help="Confirm this is not a personal server/library")
    args = parser.parse_args()
    local = args.server_data_dir / "local"
    if local.exists() and any(local.iterdir()):
        raise SystemExit("Use an empty local-source directory in a fresh disposable server")
    pages = [png(page) for page in range(1, 7)]
    names = ["Acceptance Test"] + [f"Pagination {i:03d}" for i in range(65)]
    for name in names:
        directory = local / name
        directory.mkdir(parents=True, exist_ok=True)
        for chapter in range(1, 123 if name == "Acceptance Test" else 2):
            with zipfile.ZipFile(directory / f"Chapter {chapter}.cbz", "w") as archive:
                for page, content in enumerate(pages, 1):
                    archive.writestr(f"{page:03d}.png", content)

    def graphql(query, variables=None):
        request = urllib.request.Request(
            args.url.rstrip("/") + "/api/graphql",
            data=json.dumps({"query": query, "variables": variables or {}}).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=90) as response:
            result = json.load(response)
        if result.get("errors"):
            raise SystemExit("Fixture GraphQL operation failed; inspect the disposable server")
        return result["data"]

    result = graphql('mutation {fetchSourceManga(input:{source:"0",page:1,type:POPULAR}) {mangas{id title} hasNextPage}}')["fetchSourceManga"]
    if result["hasNextPage"] or len(result["mangas"]) != 66:
        raise SystemExit("Unexpected local-source fixture pagination")
    if next(m["id"] for m in result["mangas"] if m["title"] == "Acceptance Test") != 1:
        raise SystemExit("The test manga must have ID 1; use a fresh disposable database")
    graphql("mutation($ids:[Int!]!) {updateMangas(input:{ids:$ids,patch:{inLibrary:true}}){mangas{id}}}", {"ids": [m["id"] for m in result["mangas"]]})
    result = graphql("mutation {fetchMangaAndChapters(input:{id:1,fetchManga:true,fetchChapters:true}) {chapters{id}}}")
    if len(result["fetchMangaAndChapters"]["chapters"]) != 122:
        raise SystemExit("Unexpected fixture chapter count")
    print("Created 66 library titles and 122 chapters for manga 1. Run SuwayomiLiveServerTest with this URL.")


if __name__ == "__main__":
    main()
# KMK <--
