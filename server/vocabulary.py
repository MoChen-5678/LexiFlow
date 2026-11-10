"""Versioned vocabulary snapshots; failed upstream updates keep the last good books."""
import hashlib
import json
import sqlite3
import re
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from mirrors import head, read_json
from frequency import archive_frequencies, apply_editions, METHOD
import threading

REPO = "RealKai42/qwerty-learner"
FILES = {
    "cet4": ["GaoKao_3500", "CET4_T"],
    "cet6": ["GaoKao_3500", "CET4_T", "CET6_T"],
    "ielts": ["IELTS_order", "IELTS_3_T", "IELTS_tinglichangjing"],
    "toefl": ["TOEFL_3_T", "TOEFL_ZhangHongYan"],
    "coder": ["it-words"],
}
CATEGORIES = dict(cet4="CET-4", cet6="CET-6", ielts="IELTS", toefl="TOEFL", coder="Coder")


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()


def normalize(book, groups):
    merged = {}
    for rows in groups:
        if not isinstance(rows, list) or not 10 <= len(rows) <= 30000:
            raise ValueError("invalid vocabulary source size")
        for row in rows:
            word = row.get("name", "").strip()
            trans = row.get("trans")
            if not word or len(word) > 120 or not re.fullmatch(r"[A-Za-z][A-Za-z .,'’()/&+\-0-9]*", word):
                continue
            if not isinstance(trans, list):
                raise ValueError("invalid vocabulary translation")
            translation = "；".join(t.strip() for t in trans if isinstance(t, str) and t.strip())
            if not translation:
                continue
            if len(translation) > 3000:
                raise ValueError("invalid vocabulary translation length")
            key = word.casefold()
            merged[key] = dict(word=word, translation=translation, phonetic=row.get("usphone", row.get("ukphone", "")),
                               category=CATEGORIES[book], bookIds=book)
    if len(merged) < 100:
        raise ValueError("empty vocabulary snapshot")
    return sorted(merged.values(), key=lambda row: row["word"].casefold())


class Vocabulary:
    def __init__(self, db_path, assets):
        self.path = db_path
        self.lock = threading.RLock()
        seed = Path(assets).parent / "exam_frequency.json"
        self.seed_frequency = json.loads(seed.read_text(encoding="utf8")) if seed.exists() else {}
        with closing(self.connect()) as db, db:
            db.execute("CREATE TABLE IF NOT EXISTS vocabulary (book TEXT PRIMARY KEY, version TEXT, body BLOB, count INTEGER)")
            db.execute("CREATE TABLE IF NOT EXISTS vocabulary_meta (id INTEGER PRIMARY KEY, revision TEXT, last_check TEXT, last_success TEXT, state TEXT)")
            db.execute("CREATE TABLE IF NOT EXISTS vocabulary_frequency (id INTEGER PRIMARY KEY, body TEXT)")
            if not db.execute("SELECT 1 FROM vocabulary").fetchone():
                books = {book: json.loads((Path(assets) / (book + ".json")).read_text(encoding="utf8")) for book in FILES}
                self._save(db, books)
                source = Path(assets).parent / "vocabulary_sources.json"
                revision = json.loads(source.read_text())["revision"] if source.exists() else "bundled-1.4"
                db.execute("INSERT OR REPLACE INTO vocabulary_meta VALUES(1,?,NULL,NULL,'bundled')", (revision,))
        self.refresh_editions()

    def connect(self):
        return sqlite3.connect(self.path, timeout=20)

    def _save(self, db, books):
        for book, rows in books.items():
            body = encoded(rows)
            db.execute("INSERT OR REPLACE INTO vocabulary VALUES(?,?,?,?)",
                       (book, hashlib.sha256(body).hexdigest(), body, len(rows)))

    def manifest(self):
        with closing(self.connect()) as db:
            meta = db.execute("SELECT revision,last_check,last_success,state FROM vocabulary_meta WHERE id=1").fetchone()
            books = {book: dict(version=version, count=count) for book, version, count in db.execute("SELECT book,version,count FROM vocabulary")}
            for book, body in db.execute("SELECT book,body FROM vocabulary"):
                books[book]["high_count"] = sum(book + "_high" in row.get("bookIds", "").split(",") for row in json.loads(body))
            frequency = db.execute("SELECT body FROM vocabulary_frequency WHERE id=1").fetchone()
        return dict(books=books, source=REPO, revision=meta[0], last_check=meta[1], last_success=meta[2], state=meta[3],
                    frequency=json.loads(frequency[0]) if frequency else {}, frequency_method=METHOD)

    def refresh_editions(self):
        with self.lock, closing(self.connect()) as db, db:
            frequencies = dict(self.seed_frequency)
            frequencies.update(archive_frequencies(db))
            books = {book: json.loads(body) for book, body in db.execute("SELECT book,body FROM vocabulary")}
            self._save(db, apply_editions(books, frequencies))
            summary = {book: {k: v for k, v in info.items() if k != "counts"} for book, info in frequencies.items()}
            db.execute("INSERT OR REPLACE INTO vocabulary_frequency VALUES(1,?)", (json.dumps(summary),))

    def book(self, name, version):
        with closing(self.connect()) as db:
            row = db.execute("SELECT version,body FROM vocabulary WHERE book=?", (name,)).fetchone()
        if not row:
            raise KeyError(name)
        if version != row[0]:
            raise ValueError("snapshot changed; refresh manifest")
        return row[1]

    def sync(self):
        now = datetime.now(timezone.utc).isoformat()
        with closing(self.connect()) as db, db:
            db.execute("UPDATE vocabulary_meta SET last_check=?,state='checking' WHERE id=1", (now,))
        try:
            revision = head(REPO, "master")
            if revision != self.manifest()["revision"]:
                sources = {name: read_json(REPO, revision, "public/dicts/" + name + ".json", 8_000_000)
                           for name in dict.fromkeys(name for names in FILES.values() for name in names)}
                books = {book: normalize(book, [sources[name] for name in names]) for book, names in FILES.items()}
                old = self.manifest()["books"]
                if any(len(rows) < old[book]["count"] * 0.8 for book, rows in books.items()):
                    raise ValueError("upstream book unexpectedly shrank")
                with self.lock, closing(self.connect()) as db, db:
                    frequencies = dict(self.seed_frequency)
                    frequencies.update(archive_frequencies(db))
                    apply_editions(books, frequencies)
                    self._save(db, books)
                    db.execute("UPDATE vocabulary_meta SET revision=? WHERE id=1", (revision,))
            with closing(self.connect()) as db, db:
                db.execute("UPDATE vocabulary_meta SET last_success=?,state='ok' WHERE id=1", (now,))
        except Exception:
            with closing(self.connect()) as db, db:
                db.execute("UPDATE vocabulary_meta SET state='failed' WHERE id=1")
            raise
