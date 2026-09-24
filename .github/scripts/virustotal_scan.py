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
"""Перевіряє файли релізу на VirusTotal і дописує результат в опис релізу.

Запуск (усе — зі змінних середовища, аргумент один — тека з файлами релізу):

    VT_API_KEY=...  GITHUB_TOKEN=...  GITHUB_REPOSITORY=owner/repo \
    RELEASE_TAG=v0.9.8  python3 virustotal_scan.py assets/

ЧОМУ БЕЗ СТОРОННІХ БІБЛІОТЕК

На бігуні GitHub є python3, а pip install — це ще одне місце, яке може впасти
через мережу. Тут лише стандартна бібліотека.

ЛІМІТИ БЕЗКОШТОВНОГО API

Public API VirusTotal: 4 запити на хвилину, 500 на добу. Тому:
  * кожен файл спершу шукається ЗА ХЕШЕМ — вбудована Java й бібліотеки
    між збірками зазвичай не змінюються, і такий файл коштує один запит;
  * між будь-якими двома запитами до VirusTotal — щонайменше 15 секунд;
  * якщо денний ліміт вичерпано, решта файлів позначається «не перевірено»,
    а не валить реліз.

ОПИС РЕЛІЗУ

Розділ дописується В КІНЕЦЬ опису між двома HTML-коментарями, MARK_START і
MARK_END. GitHub коментарів не показує, а скрипт за ними знаходить і замінює
свій розділ, тож при повторному запуску розділ лишається один. Старий текстовий
формат (рядок SECTION_HEAD) теж прибирається.

Що бачить людина на сторінці релізу:
  * один значок-підсумок (shields.io): вердикт, виявлення, скільки файлів;
  * по значку на кожну систему — клік відкриває звіт по повному архіву;
  * таблиця всіх файлів, згорнута в <details>, щоб не займати сторінку.

Лаунчер читає той самий опис у вікні оновлення. Він вирізає розділ між
коментарями й замість нього показує одну кольорову плашку, взявши вердикт з
атрибутів MARK_START (див. update/ScanReport.java). Формат цих атрибутів —
контракт між скриптом і лаунчером.
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

# Межі розділу в описі. Ті самі рядки пише заглушка в release-launcher.yml і
# читає лаунчер (update/ScanReport.java). Міняти — у всіх трьох місцях.
MARK_START = "<!-- virustotal:start"
MARK_END = "<!-- virustotal:end -->"
# Заголовок старого, текстового формату. Лише для того, щоб прибрати його з
# релізів, які вийшли до значків.
SECTION_HEAD = "Перевірка VirusTotal"

VT_API = os.environ.get("VT_API_BASE", "https://www.virustotal.com/api/v3")
VT_GUI = "https://www.virustotal.com/gui/file/"
GH_API = os.environ.get("GITHUB_API_URL", "https://api.github.com")

# 60 / 4 = 15 секунд, і півсекунди запасу на різницю годинників.
MIN_INTERVAL = float(os.environ.get("VT_MIN_INTERVAL", "15.5"))
# Скільки чекати, поки аналіз завершиться. Великі архіви інколи стоять у черзі
# довго; що не встигло — лишається з посиланням і позначкою «триває».
MAX_WAIT = float(os.environ.get("VT_MAX_WAIT", str(45 * 60)))
# З якої кількості "malicious" файл вважається небезпечним, а не підозрілим.
# Одне-два спрацювання на виконуваний файл jpackage — звична хибна тривога.
DANGER_AT = int(os.environ.get("VT_DANGER_THRESHOLD", "3"))

DIRECT_LIMIT = 32 * 1024 * 1024     # POST /files
UPLOAD_LIMIT = 650 * 1024 * 1024    # POST на upload_url

# Що не має сенсу слати антивірусам: опис збірки, а не програма.
SKIP_SUFFIXES = (".json", ".txt", ".md", ".sha256")

# Позначки. Порядок = серйозність, найгірша стає загальною позначкою релізу.
CLEAN, WARN, DANGER = "ЧИСТО", "УВАГА", "НЕБЕЗПЕЧНО"
PENDING, UNCHECKED, ERROR = "ТРИВАЄ", "НЕ ПЕРЕВІРЕНО", "ПОМИЛКА"
SEVERITY = [CLEAN, PENDING, UNCHECKED, ERROR, WARN, DANGER]

# Результати двигунів, які рахуються як "двигун подивився на файл".
COUNTED = ("malicious", "suspicious", "undetected", "harmless")


class QuotaExceeded(Exception):
    """Денний ліміт вичерпано — далі сьогодні нічого не вийде."""


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
        """Один запит з повторами на 429 і 5xx. Повертає (код, json)."""
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
                    # Хвилинне вікно ще не скинулось, або в них щось лягло.
                    time.sleep(60)
                    continue
                raise RuntimeError(f"VirusTotal {err.code} {code}: {payload}") from err
            except (urllib.error.URLError, TimeoutError) as err:
                if attempt == 4:
                    raise RuntimeError(f"VirusTotal недосяжний: {err}") from err
                time.sleep(30)
        raise RuntimeError(f"VirusTotal не відповів після повторів: {method} {url}")


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
    """Тіло multipart/form-data з одним полем file. Файли до 650 МБ, пам'яті
    на бігуні вистачає, а потокова відправка в urllib — окрема морока."""
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
    """(позначка, виявлень, двигунів) зі stats VirusTotal."""
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
    """Повертає список результатів у порядку файлів."""
    results = []
    pending = {}        # analysis id -> результат, що чекає на завершення
    quota_hit = False

    for path in files:
        size = path.stat().st_size
        sha = sha256_of(path)
        item = {"name": path.name, "sha": sha, "mark": UNCHECKED, "found": 0,
                "engines": 0, "note": ""}
        results.append(item)
        if quota_hit:
            item["note"] = "денний ліміт VirusTotal вичерпано"
            continue
        try:
            status, data = vt.call("GET", f"/files/{sha}")
            attrs = (data.get("data") or {}).get("attributes") or {}
            stats = attrs.get("last_analysis_stats")
            if status == 200 and stats and sum(stats.values()):
                item["mark"], item["found"], item["engines"] = verdict(stats)
                print(f"{path.name}: вже відомий, {item['mark']}")
                continue
            if size > UPLOAD_LIMIT:
                item["note"] = "більше 650 МБ, VirusTotal не приймає"
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
            print(f"{path.name}: завантажено, аналіз {analysis}")
        except QuotaExceeded:
            quota_hit = True
            item["note"] = "денний ліміт VirusTotal вичерпано"
        except Exception as err:  # один файл не має зупиняти решту
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
        item["note"] = "аналіз ще йде, результат — за посиланням"
    return results


# Коди вердикту в MARK_START. Англійською й малими літерами: їх розбирає код.
CODES = {CLEAN: "clean", WARN: "warning", DANGER: "danger",
         PENDING: "pending", UNCHECKED: "unchecked", ERROR: "error"}
ICONS = {CLEAN: "✅", WARN: "⚠️", DANGER: "⛔",
         PENDING: "⏳", UNCHECKED: "➖", ERROR: "❌"}
COLOURS = {CLEAN: "2ea44f", WARN: "d8a13c", DANGER: "b3403a",
           PENDING: "9e9e9e", UNCHECKED: "9e9e9e", ERROR: "9e9e9e"}
PLATFORMS = ("Windows", "Linux", "macOS", "Flatpak")


def platform(name):
    """Система, для якої файл, за тими самими словами, що пише ManifestTool."""
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
    """Повний архів — те, що людина качає руками. Частини — для оновлення."""
    return "-parts-" not in name.lower()


def badge(label, message, colour, logo=False):
    """URL значка shields.io. У його шляху дефіс і підкреслення службові,
    тому подвоюються; решту кодує quote."""
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

    words = {CLEAN: "чисто", WARN: "увага", DANGER: "небезпечно",
             PENDING: "перевірка триває", UNCHECKED: "перевірено не все",
             ERROR: "помилка перевірки"}[worst]
    summary = f"{words} · виявлень: {found} · файлів: {done}/{total}"

    lines = [
        f"{MARK_START} verdict={CODES[worst]} found={found} checked={done} total={total} -->",
        "",
        "---",
        "",
        f"![VirusTotal: {summary}]({badge('VirusTotal', summary, COLOURS[worst], logo=True)})",
    ]

    # По значку на систему: повний архів, бо саме його качають люди.
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
        f"<summary>Усі файли ({total}) і звіти VirusTotal · {when:%Y-%m-%d %H:%M} UTC</summary>",
        "",
        "| | Файл | Система | Виявлень | Звіт |",
        "|:-:|---|---|:-:|:-:|",
    ]
    for r in ordered:
        name = r["name"] if is_full(r["name"]) else f"{r['name']} <sub>частина оновлення</sub>"
        note = f"<br><sub>{r['note']}</sub>" if r["note"] else ""
        lines.append(f"| {ICONS[r['mark']]} | {name}{note} | {platform(r['name']) or '—'} "
                     f"| {score(r)} | [відкрити]({VT_GUI}{r['sha']}) |")
    lines += [
        "",
        f"<sub>✅ жоден антивірус нічого не знайшов · ⚠️ 1–{DANGER_AT - 1} спрацювання, "
        f"для лаунчерів на Java це зазвичай хибна тривога · ⛔ {DANGER_AT} і більше · "
        "⏳/➖/❌ результату немає, дивіться звіт</sub>",
        "",
        "</details>",
        "",
        MARK_END,
    ]
    return "\n".join(lines)


# Новий розділ: від MARK_START до MARK_END, або до кінця, якщо кінця немає
# (обірваний запуск). Разом із порожніми рядками перед ним.
_BLOCK = re.compile(
    r"(?:\r?\n)*" + re.escape(MARK_START) + r"[\s\S]*?(?:" + re.escape(MARK_END) + r"|\Z)[ \t]*")
# Старий текстовий розділ разом із лінією перед ним. Лише з початку рядка: у
# списку комітів ці слова можуть трапитися посеред рядка.
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
        sys.exit("використання: virustotal_scan.py <тека з файлами релізу>")
    key = os.environ.get("VT_API_KEY", "").strip()
    if not key:
        # Форк або репозиторій без секрету: реліз від цього не страждає.
        print("::warning::VT_API_KEY не задано, перевірку VirusTotal пропущено")
        return 0
    repo, tag = os.environ["GITHUB_REPOSITORY"], os.environ["RELEASE_TAG"]

    folder = pathlib.Path(sys.argv[1])
    files = sorted(p for p in folder.iterdir()
                   if p.is_file() and not p.name.lower().endswith(SKIP_SUFFIXES))
    if not files:
        sys.exit(f"у {folder} немає файлів для перевірки")

    vt = Vt(key)
    results = scan(vt, files)
    block = section(results, datetime.datetime.now(datetime.timezone.utc))
    print(block)

    # Опис читається заново перед записом: за ті півгодини, що йшла перевірка,
    # його могли поправити руками.
    release = github("GET", f"/repos/{repo}/releases/tags/{tag}")
    github("PATCH", f"/repos/{repo}/releases/{release['id']}",
           {"body": merge(release.get("body"), block)})
    print(f"опис релізу {tag} оновлено; запитів до VirusTotal: {vt.requests}")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(block + "\n")

    # Червоний job — щоб автор побачив проблему. Сам реліз уже опублікований і
    # від цього не зникає.
    bad = [r for r in results if r["mark"] in (DANGER, ERROR)]
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
