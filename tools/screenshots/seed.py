#!/usr/bin/env python3
# KMK -->
"""Populate only the disposable loopback screenshot server with curated SFW titles."""

import argparse
import hashlib
import json
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://127.0.0.1:4569"
SOURCE = "2499283573021220255"
FIXTURE = "yomira_sfw_screenshots_v1"
DATA = ROOT / ".local/sfw-screenshots"


def request(url, body=None, timeout=120):
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "User-Agent": "Yomira-Screenshot-Setup/1"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as response:
        raw = response.read()
    if not raw:
        return None
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return raw


def graphql(query, variables=None):
    result = request(BASE + "/api/graphql", {"query": query, "variables": variables or {}})
    if result.get("errors"):
        raise RuntimeError(result["errors"])
    return result["data"]


def set_meta(key, value):
    graphql(
        "mutation($meta:GlobalMetaTypeInput!){setGlobalMeta(input:{meta:$meta}){clientMutationId}}",
        {"meta": {"key": key, "value": value}},
    )


def seed():
    # A fixed loopback URL and marker prevent accidentally seeding a personal library.
    about = request(BASE + "/api/v1/settings/about")
    if about.get("name") != "Suwayomi-Server":
        raise RuntimeError("Expected a Suwayomi instance on 127.0.0.1:4569")
    meta = {m["key"]: m["value"] for m in graphql("{metas{nodes{key value}}}")["metas"]["nodes"]}
    library = graphql("{mangas(condition:{inLibrary:true}){nodes{id title}}}")["mangas"]["nodes"]
    if library and meta.get(FIXTURE) != "true":
        raise RuntimeError("Refusing to modify a nonempty library without the screenshot fixture marker")
    set_meta(FIXTURE, "true")
    set_meta("webUI_browseLanguages", '["en"]')

    extensions = request(BASE + "/api/v1/extension/list")
    extension = next(e for e in extensions if e["pkgName"] == "eu.kanade.tachiyomi.extension.all.mangadex")
    if not extension["installed"]:
        request(BASE + "/api/v1/extension/install/" + extension["pkgName"])
    prefs_url = BASE + f"/api/v1/source/{SOURCE}/preferences"
    prefs = request(prefs_url)
    for key, value in [("contentRating_en", ["safe"]), ("tryUsingFirstVolumeCover_en", True), ("usePort443_en", True)]:
        position = next(i for i, p in enumerate(prefs) if p["props"]["key"] == key)
        request(prefs_url, {"position": position, "value": json.dumps(value)})
    actual = {p["props"]["key"]: p["props"]["currentValue"] for p in request(prefs_url)}
    if actual["contentRating_en"] != ["safe"]:
        raise RuntimeError("Safe-only source preference was not applied")

    existing = {c["name"]: c["id"] for c in graphql("{categories{nodes{id name}}}")["categories"]["nodes"]}
    for order, name in enumerate(["My shelf", "Fantasy", "Everyday stories"], 1):
        if name not in existing:
            category = graphql(
                "mutation($name:String!,$order:Int!){createCategory(input:{name:$name,order:$order}){category{id}}}",
                {"name": name, "order": order},
            )["createCategory"]["category"]
            existing[name] = category["id"]

    DATA.mkdir(parents=True, exist_ok=True)
    (DATA / "covers").mkdir(exist_ok=True)
    previous = json.loads((DATA / "seed-report.json").read_text()) if (DATA / "seed-report.json").exists() else {"titles": []}
    catalog = json.loads(Path(__file__).with_name("catalog.json").read_text())
    report = {"server": about["version"], "fixture": FIXTURE, "titles": []}
    for item in catalog:
        identity = str(uuid.UUID(item["mangadex_id"]))
        # Validate each exact title; never choose a similarly named search result.
        metadata = request("https://api.mangadex.org/manga/" + identity)["data"]
        if metadata["attributes"]["contentRating"] != "safe":
            raise RuntimeError(f"{item['title']} is no longer rated Safe; review the catalog")
        result = graphql(
            "mutation($query:String!){fetchSourceManga(input:{source:\"" + SOURCE + "\",page:1,type:SEARCH,query:$query}){mangas{id title url}}}",
            {"query": "id:" + identity},
        )["fetchSourceManga"]["mangas"]
        manga = next(m for m in result if m["url"] == "/manga/" + identity)
        try:
            details = graphql(
                "mutation($id:Int!){fetchMangaAndChapters(input:{id:$id,fetchManga:true,fetchChapters:true}){manga{id title} chapters{id chapterNumber sourceOrder}}}",
                {"id": manga["id"]},
            )["fetchMangaAndChapters"]
        except RuntimeError as error:
            if "No chapters found" not in str(error):
                raise
            # Some licensed series retain their catalog entry but no English chapters.
            details = graphql(
                "mutation($id:Int!){fetchMangaAndChapters(input:{id:$id,fetchManga:true,fetchChapters:false}){manga{id title}}}",
                {"id": manga["id"]},
            )["fetchMangaAndChapters"]
            details["chapters"] = []
        graphql("mutation($ids:[Int!]!){updateMangas(input:{ids:$ids,patch:{inLibrary:true}}){mangas{id}}}", {"ids": [manga["id"]]})
        graphql(
            "mutation($id:Int!,$categories:[Int!]!){updateMangaCategories(input:{id:$id,patch:{addToCategories:$categories}}){clientMutationId}}",
            {"id": manga["id"], "categories": [existing["My shelf"], existing[item["category"]]]},
        )
        cover = request(BASE + f"/api/v1/manga/{manga['id']}/thumbnail")
        if not isinstance(cover, bytes):
            raise RuntimeError("Expected a cover image")
        (DATA / "covers" / f"{manga['id']}.jpg").write_bytes(cover)
        entry = {**item, "server_id": manga["id"], "display_title": details["manga"]["title"], "chapters": len(details["chapters"]), "cover_sha256": hashlib.sha256(cover).hexdigest()}
        report["titles"].append(entry)
        print(f"Added {entry['display_title']} ({entry['chapters']} English chapter records)", flush=True)
        time.sleep(0.3)
    removed = {m["server_id"] for m in previous["titles"]} - {m["server_id"] for m in report["titles"]}
    if removed:
        graphql("mutation($ids:[Int!]!){updateMangas(input:{ids:$ids,patch:{inLibrary:false}}){mangas{id}}}", {"ids": sorted(removed)})
    temporary_report = DATA / "seed-report.json.tmp"
    temporary_report.write_text(json.dumps(report, indent=2) + "\n")
    temporary_report.replace(DATA / "seed-report.json")
    print(f"Ready: {len(report['titles'])} curated Safe-rated titles at {BASE}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--disposable", action="store_true", required=True, help="Confirm port 4569 belongs to the disposable screenshot instance")
    parser.parse_args()
    seed()
# KMK <--
