"""Read pinned public source revisions into a private study bank."""
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import subprocess
import time
import threading
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

CET = os.environ.get("CET_PAST_REPO", "couldyyy9/cet-website")
IELTS = os.environ.get("IELTS_RECALL_REPO", "eugenewang5425/ielts-corpus-lab")
SOURCES = {CET: os.environ.get("CET_PAST_BRANCH", "master"), IELTS: os.environ.get("IELTS_RECALL_BRANCH", "main")}
INTERVAL = max(60, int(os.environ.get("PAST_POLL_SECONDS", "180")))
for name, branch in SOURCES.items():
    if not re.fullmatch(r"[A-Za-z0-9._-]+/[A-Za-z0-9._-]+", name) or not re.fullmatch(r"[A-Za-z0-9_./-]+", branch):
        raise ValueError("invalid source repository or branch")


def read_json(repo, rev, path, limit):
    url = f"https://raw.githubusercontent.com/{repo}/{rev}/{path}"
    with urlopen(Request(url, headers={"User-Agent": "LexiFlow-study"}), timeout=20) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("source exceeds size limit")
    return json.loads(data)


def parse_paper(paper, repo, revision):
    level, pid = paper["level"], paper["id"]
    if level not in ("cet4", "cet6") or not re.fullmatch(r"20\d\d_\d\d_[1-3]", pid):
        raise ValueError("invalid paper")
    citation = f"https://github.com/{repo}/blob/{revision}/public/bank/{level}_{pid}.json"
    result = []
    for q in paper["questions"]:
        kind, no, key = q["kind"], q["no"], q.get("answer", "")
        stem = q.get("stem", "").strip()
        passage = paper["passages"].get(q.get("passage"), {})
        context = passage.get("text", "")
        options = q.get("options", {})
        audio = ""
        if q["part"] == "listening":
            if not paper["hasAudio"]: continue
            kind = "listening"
            stem = q.get("groupNote") or "听原卷录音，选择答案。"
            audio = f"https://raw.githubusercontent.com/{repo}/{revision}/public/library/{level}/{pid}/listening.mp3"
        elif kind == "mcq":
            kind = "reading"
            if not context: continue
        elif kind == "cloze":
            options = passage.get("wordBank", {})
            if not context: continue
        elif kind == "matching":
            paragraphs = passage.get("paragraphs", {})
            options = {letter: letter for letter in paragraphs}
            context = "\n\n".join(f"{k}. {v}" for k, v in sorted(paragraphs.items()))
            if not context: continue
        else:
            continue
        if not stem or not key or key not in options or not options[key]: continue
        choices = {letter: (f"{letter}. {text}" if kind != "matching" else letter)
                   for letter, text in sorted(options.items()) if text}
        result.append(dict(id=f"past-{level}-{pid}-{no}", exam=level, kind=kind, prompt=stem,
            context=context, choices=list(choices.values()), answer=choices[key], speech="", audio=audio,
            explanation=f"原卷答案索引：{key}", label=f"{paper['title']} · 第 {no} 题",
            source=citation, origin="past", paper=pid, number=no))
    return result


def cet_rows(revision):
    manifest = read_json(CET, revision, "public/bank/manifest.json", 200000)
    def fetch(entry):
        name = entry["file"]
        if not re.fullmatch(r"cet[46]_20\d\d_\d\d_[1-3]\.json", name):
            raise ValueError("unexpected paper filename")
        paper = read_json(CET, revision, "public/bank/" + name, 1000000)
        if f"{paper['level']}_{paper['id']}.json" != name:
            raise ValueError("paper ID mismatch")
        rows = parse_paper(paper, CET, revision)
        if not rows: raise ValueError("empty paper")
        return rows
    if not 0 < len(manifest["exams"]) <= 1000:
        raise ValueError("invalid paper manifest")
    with ThreadPoolExecutor(max_workers=5) as pool:
        return [q for group in pool.map(fetch, manifest["exams"]) for q in group]


def recall_rows(data):
    result = {}
    for topic in data["topics"]:
        for q in topic["questions"]:
            refs = [data["sources"][ref] for ref in q["sourceRefs"]]
            refs = [s for s in refs if s["status"] == "test_taker_recall"
                    and s.get("phase") != "upcoming" and re.fullmatch(r"20\d\d-\d\d", s.get("period", ""))
                    and s["period"] <= datetime.now(timezone.utc).strftime("%Y-%m")
                    and s.get("url", "").startswith("https://") and s.get("region", "").strip() not in ("", "未标注", "Unknown")]
            if not refs or not q["text"].strip(): continue
            key = "recall-" + q["id"]
            refs.sort(key=lambda s: (s["period"], s["id"]), reverse=True)
            ref = refs[0]
            result[key] = dict(id=key, exam="ielts", kind="speaking", prompt=q["text"], context="",
                answer="", choices=[], explanation="", speech=q["text"], audio="", source=ref["url"],
                label=f"考生回忆 · {ref['period']} · {ref['region']} · {q['part']}", origin="recall",
                references=[dict(period=s["period"], region=s["region"], url=s["url"]) for s in refs])
    return list(result.values())


class Archive:
    def __init__(self, db_path):
        self.path = db_path
        self.wake = threading.Event()
        with closing(self.connect()) as db, db:
            db.execute("CREATE TABLE IF NOT EXISTS past_items(id TEXT PRIMARY KEY, repo TEXT, exam TEXT, kind TEXT, body TEXT)")
            db.execute("CREATE INDEX IF NOT EXISTS past_filter ON past_items(exam,kind)")
            db.execute("""CREATE TABLE IF NOT EXISTS source_state(repo TEXT PRIMARY KEY, revision TEXT,
                checked TEXT, error TEXT, total INTEGER)""")
            if "parser" not in {row[1] for row in db.execute("PRAGMA table_info(source_state)")}:
                db.execute("ALTER TABLE source_state ADD COLUMN parser INTEGER DEFAULT 0")
        self.key_path = Path(db_path).parent / "study-key"
        if not self.key_path.exists():
            key = secrets.token_urlsafe(18)
            with os.fdopen(os.open(self.key_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "w") as output:
                output.write(key + "\n")
        self.key = self.key_path.read_text().strip()
        if not self.key: raise ValueError("empty study key")

    def connect(self):
        return sqlite3.connect(self.path, timeout=20)

    def sync(self, repo):
        moment = datetime.now(timezone.utc).isoformat()
        try:
            with closing(self.connect()) as db:
                saved = db.execute("SELECT revision,parser FROM source_state WHERE repo=?", (repo,)).fetchone()
            output = subprocess.run(["git", "ls-remote", "--exit-code", f"https://github.com/{repo}.git",
                    "refs/heads/" + SOURCES[repo]], capture_output=True, text=True, check=True, timeout=20,
                    env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
            revision = output.stdout.split()[0]
            if not re.fullmatch(r"[a-f0-9]{40}", revision): raise ValueError("invalid revision")
            if saved == (revision, 1):
                with closing(self.connect()) as db, db:
                    db.execute("UPDATE source_state SET checked=?,error=NULL WHERE repo=?", (moment, repo))
                return
            rows = cet_rows(revision) if repo == CET else recall_rows(read_json(repo, revision, "data/speaking.json", 12000000))
            if not rows or len({row["id"] for row in rows}) != len(rows): raise ValueError("empty or duplicate source records")
            with closing(self.connect()) as db, db:
                for exam in ("cet4", "cet6") if repo == CET else ("ielts",):
                    db.execute("DELETE FROM source_state WHERE repo IN (SELECT repo FROM past_items WHERE exam=?)", (exam,))
                    db.execute("DELETE FROM past_items WHERE exam=?", (exam,))
                db.executemany("INSERT INTO past_items VALUES(?,?,?,?,?)",
                    [(r["id"], repo, r["exam"], r["kind"], json.dumps(r, ensure_ascii=False)) for r in rows])
                db.execute("INSERT OR REPLACE INTO source_state(repo,revision,checked,error,total,parser) VALUES(?,?,?,NULL,?,1)", (repo, revision, moment, len(rows)))
        except Exception as exc:
            with closing(self.connect()) as db, db:
                db.execute("""INSERT INTO source_state(repo,revision,checked,error,total) VALUES(?,NULL,?,?,0)
                    ON CONFLICT(repo) DO UPDATE SET checked=excluded.checked,error=excluded.error""",
                           (repo, moment, str(exc)[:300]))

    def monitor(self):
        while True:
            self.wake.clear()
            with ThreadPoolExecutor(max_workers=2) as pool:
                list(pool.map(self.sync, SOURCES))
            self.wake.wait(INTERVAL)

    def status(self):
        with closing(self.connect()) as db:
            db.row_factory = sqlite3.Row
            return [dict(row) for row in db.execute("SELECT * FROM source_state ORDER BY repo")]

    def papers(self, exam):
        with closing(self.connect()) as db:
            return [row[0] for row in db.execute("""SELECT DISTINCT json_extract(body,'$.paper') AS paper
                FROM past_items WHERE exam=? ORDER BY paper DESC""", (exam,))]

    def questions(self, exam, kind, limit, paper="", after=0):
        if paper:
            with closing(self.connect()) as db:
                return [json.loads(row[0]) for row in db.execute("""SELECT body FROM past_items WHERE exam=?
                    AND (?='all' OR kind=?) AND json_extract(body,'$.paper')=?
                    AND json_extract(body,'$.number')>? ORDER BY json_extract(body,'$.number') LIMIT ?""",
                    (exam, kind, kind, paper, after, limit))]

        with closing(self.connect()) as db:
            return [json.loads(row[0]) for row in db.execute("""SELECT body FROM past_items
                WHERE exam=? AND (?='all' OR kind=?) ORDER BY random() LIMIT ?""", (exam, kind, kind, limit))]

    def authorized(self, env):
        stamp = env.get("HTTP_X_STUDY_TIME", "")
        if not stamp.isascii() or not stamp.isdecimal() or len(stamp) > 12 or abs(time.time() - int(stamp)) > 300:
            return False
        message = stamp + "\n" + env["PATH_INFO"] + "?" + env.get("QUERY_STRING", "")
        digest = hmac.new(self.key.encode(), message.encode(), hashlib.sha256).hexdigest()
        return hmac.compare_digest(digest.encode(), env.get("HTTP_X_STUDY_SIGNATURE", "").encode())

    def webhook(self, env):
        secret = os.environ.get("GITHUB_WEBHOOK_SECRET", "")
        if not secret: return "404 Not Found"
        length = env.get("CONTENT_LENGTH", "")
        if not length.isdecimal() or len(length)>7 or not 0 < int(length) <= 2000000: return "400 Bad Request"
        body = env["wsgi.input"].read(int(length))
        signature = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
        if not hmac.compare_digest(signature, env.get("HTTP_X_HUB_SIGNATURE_256", "")): return "403 Forbidden"
        if env.get("HTTP_X_GITHUB_EVENT") == "push":
            try:
                event = json.loads(body)
                repo = event["repository"]["full_name"]
                if repo in SOURCES and event["ref"] == "refs/heads/" + SOURCES[repo]: self.wake.set()
            except (KeyError, TypeError, ValueError):
                return "400 Bad Request"
        return "202 Accepted"
