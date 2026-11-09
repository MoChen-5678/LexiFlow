import json
import logging
from functools import partial
from contextlib import closing
import os
import subprocess
import threading
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request, urlopen

from past import Archive, SOURCES
from mirrors import head, read_json
from schedule import daily, DAY
from bank import Bank, EXAMS, KINDS, daily_questions

ROOT = Path(__file__).resolve().parent
DB = Bank(os.environ.get("LEXIFLOW_DB", "/data/questions.sqlite3"))
ARCHIVE = Archive(DB.path)
for repo, branch in SOURCES.items():
    daily(DB.path, f"past:{repo}:{branch}", partial(ARCHIVE.sync, repo))
DICTS = Path(os.environ.get("LEXIFLOW_DICTS", ROOT / "dicts"))
FEED = os.environ.get("QUESTION_FEED_URL", "").strip()
audio_slots = threading.BoundedSemaphore(2)
with closing(DB.connect()) as db, db:
    db.execute("CREATE TABLE IF NOT EXISTS feed_status (id INTEGER PRIMARY KEY, state TEXT, last_success TEXT)")
    previous = db.execute("SELECT state,last_success FROM feed_status WHERE id=1").fetchone()
status = {"source": previous[0] if previous else "pending", "last_success": previous[1] if previous else None}


def today():
    return datetime.now(timezone.utc).date().isoformat()


def import_feed():
    if FEED:
        if urlsplit(FEED).scheme != "https":
            raise ValueError("feed requires HTTPS")
        with urlopen(Request(FEED, headers={"User-Agent": "LexiFlow/1.0"}), timeout=15) as response:
            if urlsplit(response.geturl()).scheme != "https":
                raise ValueError("feed redirected outside HTTPS")
            payload = response.read(2_000_001)
        if len(payload) > 2_000_000:
            raise ValueError("feed exceeds 2 MB")
        rows = json.loads(payload)
    else:
        repo = "MoChen-5678/LexiFlow"
        path = "app/src/main/assets/exam_questions.json"
        try:
            rows = read_json(repo, head(repo, "main"), path, 2_000_000)
        except FileNotFoundError:
            rows = read_json(repo, head(repo, "codex/exam-practice"), path, 2_000_000)
    if not isinstance(rows, list) or not rows or len(rows) > 3000:
        raise ValueError("invalid question feed")
    if any(not isinstance(row, dict) or str(row.get("id", "")).startswith("daily-") for row in rows):
        raise ValueError("reserved daily question ID")
    DB.save(rows, today())


def refresh():
    global status
    try:
        daily_questions(DB, DICTS, today())
        import_feed()
        status = {"source": "ok", "last_success": datetime.now(timezone.utc).isoformat()}
    except Exception as exc:
        logging.warning("Question refresh failed: %s", exc)
        status = {**status, "source": "failed"}
    with closing(DB.connect()) as db, db:
        db.execute("INSERT OR REPLACE INTO feed_status VALUES(1,?,?)", (status["source"], status["last_success"]))


existing_ids = DB.existing()
DB.save([row for row in json.loads((ROOT / "questions.json").read_text(encoding="utf8"))
         if row["id"] not in existing_ids], today())
daily_questions(DB, DICTS, today())
daily(DB.path, "practice:", refresh)


def deadlines():
    with closing(DB.connect()) as db:
        return {job.rstrip(":"): datetime.fromtimestamp(due, timezone.utc).isoformat()
                for job, due in db.execute("SELECT name,due FROM sync_clock")}


def app(env, start_response):
    def send(code, data, mime="application/json; charset=utf-8"):
        if not isinstance(data, bytes):
            data = json.dumps(data, ensure_ascii=False).encode()
        start_response(code, [("Content-Type", mime), ("Content-Length", str(len(data))),
                              ("X-Content-Type-Options", "nosniff")])
        return [data]

    if env["REQUEST_METHOD"] != "GET":
        return send("405 Method Not Allowed", {"error": "GET required"})
    path = env["PATH_INFO"]
    params = parse_qs(env.get("QUERY_STRING", ""))
    def param(name, default=""):
        return params.get(name, [default])[0]
    if path == "/health":
        return send("200 OK", {"status": "ready", "update": status, "past_sources": ARCHIVE.status(), "check_interval_seconds": DAY,
                               "next_checks_utc": deadlines()})
    if path == "/api/papers" and param("exam") in ("cet4", "cet6"):
        return send("200 OK", {"papers": ARCHIVE.papers(param("exam"))})
    if path == "/api/questions":
        exam, kind, limit = param("exam"), param("kind", "all"), param("limit", "10")
        origin = param("origin", "practice")
        if origin not in ("past", "practice"):
            return send("400 Bad Request", {"error": "invalid origin"})
        allowed = (*KINDS, "all", "speaking", "matching") if origin == "past" else (*KINDS, "all")
        if exam not in EXAMS or kind not in allowed or not limit.isascii() or not limit.isdecimal() or len(limit) > 2 or not 1 <= int(limit) <= 30:
            return send("400 Bad Request", {"error": "invalid exam, kind or limit"})
        if origin == "past":
            if not ARCHIVE.authorized(env):
                return send("403 Forbidden", {"error": "study key required"})
            paper, after = param("paper"), param("after", "0")
            if not after.isascii() or not after.isdecimal() or len(after)>3 or len(paper)>16:
                return send("400 Bad Request", {"error": "invalid paper or question number"})
            return send("200 OK", {"questions": ARCHIVE.questions(exam, kind, min(4, int(limit)), paper, int(after))})
        return send("200 OK", {"questions": DB.batch(exam, kind, int(limit))})
    if path == "/api/audio":
        text, accent, speed = param("text").strip(), param("accent", "US"), param("speed", "1")
        try:
            rate = float(speed)
        except ValueError:
            rate = 0
        if not 0.5 <= rate <= 2 or accent not in ("US", "UK") or not 0 < len(text) <= 1000:
            return send("400 Bad Request", {"error": "invalid speech request"})
        if not audio_slots.acquire(blocking=False):
            return send("503 Service Unavailable", {"error": "speech engine busy"})
        try:
            audio = subprocess.run(["espeak-ng", "--stdout", "-v", "en-us" if accent == "US" else "en-gb",
                                    "-s", str(round(160 * rate)), "--stdin"], input=text.encode(),
                                   stdout=subprocess.PIPE, timeout=8, check=True).stdout
            # eSpeak writes unknown lengths to a pipe. Android players expect a finite RIFF file.
            if audio[:4] != b"RIFF" or audio[36:40] != b"data" or len(audio) <= 44:
                return send("503 Service Unavailable", {"error": "invalid speech output"})
            audio = (audio[:4] + (len(audio) - 8).to_bytes(4, "little") + audio[8:40]
                     + (len(audio) - 44).to_bytes(4, "little") + audio[44:])
            return send("200 OK", audio, "audio/wav")
        except (OSError, subprocess.SubprocessError):
            return send("503 Service Unavailable", {"error": "speech unavailable"})
        finally:
            audio_slots.release()
    return send("404 Not Found", {"error": "not found"})
