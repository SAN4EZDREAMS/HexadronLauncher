#!/usr/bin/env python3
# HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
# Copyright (c) 2026 OLEKSII RADCHUK (SAN4EZDREAMS). All rights reserved.
#
# Licensed for noncommercial use only. You may use, study, share and improve
# this software; you may not sell it, and you may not remove, alter or obscure
# this notice or the authorship it records. Full terms: LICENSE.md in the
# project root. Provided without any warranty.
#
# SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
"""Scans the release files on VirusTotal and adds the result to the release notes.

Usage (everything comes from environment variables; the only argument is the
folder with the release files):

    VT_API_KEY=...  GITHUB_TOKEN=...  GITHUB_REPOSITORY=owner/repo \
    RELEASE_TAG=v0.9.8  python3 virustotal_scan.py assets/

WHY NO THIRD-PARTY LIBRARIES

The GitHub runner has python3, and pip install is one more place that can fail
because of the network. Only the standard library is used here.

FREE API LIMITS

VirusTotal Public API: 4 requests per minute, 500 per day. Because of this:
  * each file is first looked up BY HASH - the bundled Java and the libraries
    usually do not change between builds, and such a file costs one request;
  * there are at least 15 seconds between any two requests to VirusTotal;
  * when the daily limit is used up, the remaining files are marked "unchecked",
    and the release does not fail.

RELEASE NOTES

The section is added AT THE END of the notes, between two HTML comments,
MARK_START and MARK_END. GitHub does not show comments, and the script uses
them to find and replace its section, so a repeated run still leaves one
section. The old text format (the SECTION_HEAD line) is removed too.

What a person sees on the release page:
  * one summary badge (shields.io): verdict, detections, number of files;
  * one badge per system - a click opens the report for the full archive;
  * a table of all files, collapsed in <details> so that it does not fill the page.

The launcher reads the same notes in the update window. It cuts out the section
between the comments and shows one coloured banner instead, with the verdict
taken from the MARK_START attributes (see update/ScanReport.java). The format
of these attributes is a contract between the script and the launcher.
"""

import datetime
import hashlib
import json
import os
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request
import uuid

# Section boundaries in the notes. The placeholder in release-launcher.yml writes
# the same strings, and the launcher reads them (update/ScanReport.java). Change
# them in all three places.
MARK_START = "<!-- virustotal:start"
MARK_END = "<!-- virustotal:end -->"
# The heading of the old text format (Ukrainian for "VirusTotal check", written
# as escapes). It is here only to remove it from releases made before the badges.
SECTION_HEAD = "\u041f\u0435\u0440\u0435\u0432\u0456\u0440\u043a\u0430 VirusTotal"

VT_API = os.environ.get("VT_API_BASE", "https://www.virustotal.com/api/v3")
VT_GUI = "https://www.virustotal.com/gui/file/"
GH_API = os.environ.get("GITHUB_API_URL", "https://api.github.com")

# 60 / 4 = 15 seconds, plus half a second of margin for clock differences.
MIN_INTERVAL = float(os.environ.get("VT_MIN_INTERVAL", "15.5"))
# How long to wait for the analysis to finish. Large archives sometimes wait in
# the queue for a long time; a file that does not finish in time keeps its link
# and the "pending" mark.
MAX_WAIT = float(os.environ.get("VT_MAX_WAIT", str(45 * 60)))
# From how many "malicious" detections a file counts as dangerous, not suspicious.
# One or two detections on a jpackage executable are a usual false alarm.
DANGER_AT = int(os.environ.get("VT_DANGER_THRESHOLD", "3"))

DIRECT_LIMIT = 32 * 1024 * 1024     # POST /files
UPLOAD_LIMIT = 650 * 1024 * 1024    # POST to upload_url

# Files that it makes no sense to send to antivirus engines: build descriptions, not programs.
SKIP_SUFFIXES = (".json", ".txt", ".md", ".sha256")

# Marks. The order is the severity; the worst one becomes the mark of the release.
CLEAN, WARN, DANGER = "CLEAN", "WARNING", "DANGER"
PENDING, UNCHECKED, ERROR = "PENDING", "UNCHECKED", "ERROR"
SEVERITY = [CLEAN, PENDING, UNCHECKED, ERROR, WARN, DANGER]

# Engine results that count as "the engine looked at the file".
COUNTED = ("malicious", "suspicious", "undetected", "harmless")


class QuotaExceeded(Exception):
    """The daily limit is used up - nothing more can be done today."""


class Vt:
    def __init__(self, key):
        self.key = key
        self.last = 0.0
        self.requests = 0

    def _wait_turn(self):
        pause = self.last + MIN_INTERVAL - time.monotonic()
        if pause > 0:
            time.sleep(pause)
        self.last = time.monotonic()
        self.requests += 1

    def call(self, method, url, body=None, headers=None, timeout=120):
        """One request, retried on 429 and 5xx. Returns (code, json)."""
        if not url.startswith("http"):
            url = VT_API + url
        for attempt in range(5):
            self._wait_turn()
            req = urllib.request.Request(url, data=body, method=method)
            req.add_header("x-apikey", self.key)
            req.add_header("accept", "application/json")
            for name, value in (headers or {}).items():
                req.add_header(name, value)
            try:
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    return resp.status, json.loads(resp.read() or b"{}")
            except urllib.error.HTTPError as err:
                payload = _json_or_empty(err.read())
                code = (payload.get("error") or {}).get("code", "")
                if err.code == 404:
                    return 404, payload
                if err.code == 429 and code == "QuotaExceededError":
                    raise QuotaExceeded() from err
                if err.code == 429 or err.code >= 500:
                    # The per-minute window has not reset yet, or something is down on their side.
                    time.sleep(60)
                    continue
                raise RuntimeError(f"VirusTotal {err.code} {code}: {payload}") from err
            except (urllib.error.URLError, TimeoutError) as err:
                if attempt == 4:
                    raise RuntimeError(f"VirusTotal is unreachable: {err}") from err
                time.sleep(30)
        raise RuntimeError(f"VirusTotal did not answer after retries: {method} {url}")


def _json_or_empty(raw):
    try:
        return json.loads(raw or b"{}")
    except ValueError:
        return {}


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def multipart(path):
    """A multipart/form-data body with one field, file. Files are up to 650 MB,
    the runner has enough memory, and streaming uploads in urllib are a separate problem."""
    boundary = uuid.uuid4().hex
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode()
    tail = f"\r\n--{boundary}--\r\n".encode()
    body = head + path.read_bytes() + tail
    return body, {"Content-Type": f"multipart/form-data; boundary={boundary}"}


def verdict(stats):
    """(mark, detections, engines) from the VirusTotal stats."""
    malicious = int(stats.get("malicious", 0))
    suspicious = int(stats.get("suspicious", 0))
    engines = sum(int(stats.get(k, 0)) for k in COUNTED)
    if malicious >= DANGER_AT:
        mark = DANGER
    elif malicious or suspicious:
        mark = WARN
    else:
        mark = CLEAN
    return mark, malicious + suspicious, engines


def scan(vt, files):
    """Returns the list of results in the order of the files."""
    results = []
    pending = {}        # analysis id -> result that waits for completion
    quota_hit = False

    for path in files:
        size = path.stat().st_size
        sha = sha256_of(path)
        item = {"name": path.name, "sha": sha, "mark": UNCHECKED, "found": 0,
                "engines": 0, "note": ""}
        results.append(item)
        if quota_hit:
            item["note"] = "the VirusTotal daily limit is used up"
            continue
        try:
            status, data = vt.call("GET", f"/files/{sha}")
            attrs = (data.get("data") or {}).get("attributes") or {}
            stats = attrs.get("last_analysis_stats")
            if status == 200 and stats and sum(stats.values()):
                item["mark"], item["found"], item["engines"] = verdict(stats)
                print(f"{path.name}: already known, {item['mark']}")
                continue
            if size > UPLOAD_LIMIT:
                item["note"] = "larger than 650 MB, VirusTotal does not accept it"
                continue
            if size > DIRECT_LIMIT:
                _, data = vt.call("GET", "/files/upload_url")
                url = data["data"]
            else:
                url = "/files"
            body, headers = multipart(path)
            _, data = vt.call("POST", url, body=body, headers=headers, timeout=900)
            analysis = data["data"]["id"]
            item["mark"] = PENDING
            pending[analysis] = item
            print(f"{path.name}: uploaded, analysis {analysis}")
        except QuotaExceeded:
            quota_hit = True
            item["note"] = "the VirusTotal daily limit is used up"
        except Exception as err:  # one file must not stop the others
            item["mark"], item["note"] = ERROR, str(err)[:200]
            print(f"{path.name}: {err}", file=sys.stderr)

    deadline = time.monotonic() + MAX_WAIT
    while pending and not quota_hit and time.monotonic() < deadline:
        for analysis, item in list(pending.items()):
            try:
                _, data = vt.call("GET", f"/analyses/{analysis}")
            except QuotaExceeded:
                quota_hit = True
                break
            except Exception as err:
                print(f"{item['name']}: {err}", file=sys.stderr)
                continue
            attrs = (data.get("data") or {}).get("attributes") or {}
            if attrs.get("status") == "completed":
                item["mark"], item["found"], item["engines"] = verdict(attrs.get("stats") or {})
                print(f"{item['name']}: {item['mark']}")
                del pending[analysis]
    for item in pending.values():
        item["note"] = "the analysis is still running, the result is at the link"
    return results


# Verdict codes in MARK_START. In English and in lower case: code parses them.
CODES = {CLEAN: "clean", WARN: "warning", DANGER: "danger",
         PENDING: "pending", UNCHECKED: "unchecked", ERROR: "error"}
ICONS = {CLEAN: "✅", WARN: "⚠️", DANGER: "⛔",
         PENDING: "⏳", UNCHECKED: "➖", ERROR: "❌"}
COLOURS = {CLEAN: "2ea44f", WARN: "d8a13c", DANGER: "b3403a",
           PENDING: "9e9e9e", UNCHECKED: "9e9e9e", ERROR: "9e9e9e"}
PLATFORMS = ("Windows", "Linux", "macOS", "Flatpak")


def platform(name):
    """The system the file is for, by the same words that ManifestTool writes."""
    lower = name.lower()
    if lower.endswith(".flatpak"):
        return "Flatpak"
    for words, label in ((("windows", "-win-"), "Windows"),
                         (("linux", "-lnx-"), "Linux"),
                         (("macos", "-darwin-"), "macOS")):
        if any(w in lower for w in words):
            return label
    return ""


def is_full(name):
    """A full archive is what a person downloads by hand. Parts are for updates."""
    return "-parts-" not in name.lower()


def badge(label, message, colour, logo=False):
    """The URL of a shields.io badge. In its path the hyphen and the underscore are
    special, so they are doubled; quote encodes the rest."""
    from urllib.parse import quote

    def part(text):
        return quote(text.replace("-", "--").replace("_", "__"), safe="")

    url = f"https://img.shields.io/badge/{part(label)}-{part(message)}-{colour}"
    return url + ("?logo=virustotal&logoColor=white" if logo else "")


def score(r):
    return f"{r['found']}/{r['engines']}" if r["engines"] else "—"


def section(results, when):
    worst = max((r["mark"] for r in results), key=SEVERITY.index, default=UNCHECKED)
    found = sum(r["found"] for r in results)
    done = sum(1 for r in results if r["engines"])
    total = len(results)

    words = {CLEAN: "clean", WARN: "warning", DANGER: "dangerous",
             PENDING: "scan in progress", UNCHECKED: "not all checked",
             ERROR: "scan error"}[worst]
    summary = f"{words} · detections: {found} · files: {done}/{total}"

    lines = [
        f"{MARK_START} verdict={CODES[worst]} found={found} checked={done} total={total} -->",
        "",
        "---",
        "",
        f"![VirusTotal: {summary}]({badge('VirusTotal', summary, COLOURS[worst], logo=True)})",
    ]

    # One badge per system: the full archive, because that is what people download.
    row = []
    for label in PLATFORMS:
        full = [r for r in results if is_full(r["name"]) and platform(r["name"]) == label]
        if not full:
            continue
        r = full[0]
        row.append(f"[![{label}: {score(r)}]({badge(label, score(r), COLOURS[r['mark']])})]"
                   f"({VT_GUI}{r['sha']})")
    if row:
        lines += ["", " ".join(row)]

    ordered = sorted(results, key=lambda r: (
        not is_full(r["name"]),
        PLATFORMS.index(platform(r["name"])) if platform(r["name"]) else len(PLATFORMS),
        r["name"]))
    lines += [
        "",
        "<details>",
        f"<summary>All files ({total}) and VirusTotal reports · {when:%Y-%m-%d %H:%M} UTC</summary>",
        "",
        "| | File | System | Detections | Report |",
        "|:-:|---|---|:-:|:-:|",
    ]
    for r in ordered:
        name = r["name"] if is_full(r["name"]) else f"{r['name']} <sub>update part</sub>"
        note = f"<br><sub>{r['note']}</sub>" if r["note"] else ""
        lines.append(f"| {ICONS[r['mark']]} | {name}{note} | {platform(r['name']) or '—'} "
                     f"| {score(r)} | [open]({VT_GUI}{r['sha']}) |")
    lines += [
        "",
        f"<sub>✅ no antivirus found anything · ⚠️ 1–{DANGER_AT - 1} detections, "
        f"usually a false alarm for Java launchers · ⛔ {DANGER_AT} or more · "
        "⏳/➖/❌ no result, see the report</sub>",
        "",
        "</details>",
        "",
        MARK_END,
    ]
    return "\n".join(lines)


# The new section: from MARK_START to MARK_END, or to the end when there is no end
# (an interrupted run). Together with the blank lines before it.
_BLOCK = re.compile(
    r"(?:\r?\n)*" + re.escape(MARK_START) + r"[\s\S]*?(?:" + re.escape(MARK_END) + r"|\Z)[ \t]*")
# The old text section, together with the line before it. Only from the start of a
# line: the list of commits can contain these words in the middle of a line.
_OLD_SECTION = re.compile(
    r"(?:\r?\n)*(?:^---[ \t]*\r?\n(?:[ \t]*\r?\n)*)?^" + re.escape(SECTION_HEAD) + r"[\s\S]*\Z",
    re.MULTILINE)


def merge(body, block):
    kept = _OLD_SECTION.sub("", _BLOCK.sub("", body or "")).rstrip()
    return f"{kept}\n\n{block}\n" if kept else f"{block}\n"


def github(method, path, payload=None):
    req = urllib.request.Request(
        GH_API + path,
        data=None if payload is None else json.dumps(payload).encode(),
        method=method)
    req.add_header("Authorization", f"Bearer {os.environ['GITHUB_TOKEN']}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read())


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: virustotal_scan.py <folder with the release files>")
    key = os.environ.get("VT_API_KEY", "").strip()
    if not key:
        # A fork or a repository without the secret: the release is not affected.
        print("::warning::VT_API_KEY is not set, VirusTotal scan skipped")
        return 0
    repo, tag = os.environ["GITHUB_REPOSITORY"], os.environ["RELEASE_TAG"]

    folder = pathlib.Path(sys.argv[1])
    files = sorted(p for p in folder.iterdir()
                   if p.is_file() and not p.name.lower().endswith(SKIP_SUFFIXES))
    if not files:
        sys.exit(f"no files to scan in {folder}")

    vt = Vt(key)
    results = scan(vt, files)
    block = section(results, datetime.datetime.now(datetime.timezone.utc))
    print(block)

    # Read the notes again before writing: somebody could edit them by hand during
    # the half hour of the scan.
    release = github("GET", f"/repos/{repo}/releases/tags/{tag}")
    github("PATCH", f"/repos/{repo}/releases/{release['id']}",
           {"body": merge(release.get("body"), block)})
    print(f"release notes for {tag} updated; requests to VirusTotal: {vt.requests}")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(block + "\n")

    # A red job, so that the author sees the problem. The release is already
    # published and does not disappear because of this.
    bad = [r for r in results if r["mark"] in (DANGER, ERROR)]
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
