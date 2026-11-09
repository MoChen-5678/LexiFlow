import json
import logging
import os
import subprocess
import threading
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request, urlopen

from bank import Bank, EXAMS, KINDS, daily_questions

ROOT = Path(__file__).resolve().parent
DB = Bank(os.environ.get("LEXIFLOW_DB", "/data/questions.sqlite3"))
DICTS = Path(os.environ.get("LEXIFLOW_DICTS", ROOT / "dicts"))
FEED = os.environ.get("QUESTION_FEED_URL", "https://raw.githubusercontent.com/MoChen-5678/LexiFlow/main/app/src/main/assets/exam_questions.json")
INTERVAL = float(os.environ.get("UPDATE_HOURS", "24")) * 3600
if not 1 <= INTERVAL <= 30 * 86400:
    raise ValueError("UPDATE_HOURS must be between one second and thirty days")
audio_slots = threading.BoundedSemaphore(2)
status = {"source": "pending", "last_success": None}


def today():
    return datetime.now(timezone.utc).date().isoformat()


def import_feed(url):
    if urlsplit(url).scheme != "https":
        raise ValueError("feed requires HTTPS")
    with urlopen(Request(url, headers={"User-Agent": "LexiFlow/1.0"}), timeout=12) as response:
        if urlsplit(response.geturl()).scheme != "https":
            raise ValueError("feed redirected outside HTTPS")
        payload = response.read(2_000_001)
    if len(payload) > 2_000_000:
        raise ValueError("feed exceeds 2 MB")
    rows = json.loads(payload)
    if not isinstance(rows, list) or not rows or len(rows) > 3000:
        raise ValueError("invalid question feed")
    if any(not isinstance(row, dict) or str(row.get("id", "")).startswith("daily-") for row in rows):
        raise ValueError("reserved daily question ID")
    DB.save(rows, today())


def refresh():
    global status
    while True:
        try:
            daily_questions(DB, DICTS, today())
            try:
                import_feed(FEED)
            except HTTPError as exc:
                canonical = "https://raw.githubusercontent.com/MoChen-5678/LexiFlow/main/app/src/main/assets/exam_questions.json"
                if exc.code != 404 or FEED != canonical:
                    raise
                import_feed(canonical.replace("/main/", "/codex/exam-practice/"))
            status = {"source": "ok", "last_success": datetime.now(timezone.utc).isoformat()}
        except Exception as exc:
            logging.warning("Question refresh failed: %s", exc)
            status = {**status, "source": "retrying"}
        threading.Event().wait(INTERVAL if status["source"] == "ok" else min(900, INTERVAL))


existing_ids = DB.existing()
DB.save([row for row in json.loads((ROOT / "questions.json").read_text(encoding="utf8"))
         if row["id"] not in existing_ids], today())
daily_questions(DB, DICTS, today())
threading.Thread(target=refresh, daemon=True, name="question-updater").start()


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
        return send("200 OK", {"status": "ready", "update": status})
    if path == "/api/questions":
        exam, kind, limit = param("exam"), param("kind", "all"), param("limit", "10")
        if exam not in EXAMS or kind not in (*KINDS, "all") or not limit.isascii() or not limit.isdecimal() or len(limit) > 2 or not 1 <= int(limit) <= 30:
            return send("400 Bad Request", {"error": "invalid exam, kind or limit"})
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
