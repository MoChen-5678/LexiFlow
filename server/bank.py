import hashlib
import json
import random
import re
import sqlite3
from contextlib import closing
from pathlib import Path

EXAMS = ("cet4", "cet6", "ielts")
KINDS = ("reading", "listening", "cloze", "vocabulary", "dictation")


def validate(item):
    if not isinstance(item, dict) or item.get("exam") not in EXAMS or item.get("kind") not in KINDS:
        raise ValueError("unknown exam or question type")
    for key, limit in (("id", 120), ("prompt", 2000), ("answer", 300), ("explanation", 1200)):
        value = item.get(key)
        if not isinstance(value, str) or not value.strip() or len(value) > limit:
            raise ValueError(f"invalid {key}")
    speech = item.get("speech", "")
    if not isinstance(speech, str) or len(speech) > 1000:
        raise ValueError("invalid speech text")
    if item["kind"] in ("listening", "dictation") and not speech.strip():
        raise ValueError("missing listening script")
    choices = item.get("choices", [])
    if (not isinstance(choices, list) or len(choices) > 6
            or any(not isinstance(x, str) or not x.strip() or len(x) > 300 for x in choices)):
        raise ValueError("invalid options")
    if choices and (len(set(choices)) != len(choices) or len(choices) < 2 or item["answer"] not in choices):
        raise ValueError("invalid choice answer")
    return {key: item[key] for key in ("id", "exam", "kind", "prompt", "answer", "explanation")} | {
        "speech": speech, "choices": choices}


class Bank:
    def __init__(self, path):
        self.path = path
        with closing(self.connect()) as db, db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""CREATE TABLE IF NOT EXISTS questions (
                id TEXT PRIMARY KEY, exam TEXT, kind TEXT, body TEXT, added TEXT)""")
            db.execute("CREATE INDEX IF NOT EXISTS exam_kind ON questions(exam,kind)")
            db.execute("CREATE TABLE IF NOT EXISTS updates (day TEXT PRIMARY KEY)")

    def connect(self):
        return sqlite3.connect(self.path, timeout=15)

    def save(self, rows, day, *, daily=False):
        rows = [validate(row) for row in rows]
        if len({row["id"] for row in rows}) != len(rows):
            raise ValueError("duplicate question ID")
        with closing(self.connect()) as db, db:
            if daily and not db.execute("INSERT OR IGNORE INTO updates VALUES (?)", (day,)).rowcount:
                return
            for row in rows:
                db.execute("""INSERT INTO questions VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET exam=excluded.exam, kind=excluded.kind, body=excluded.body""",
                           (row["id"], row["exam"], row["kind"], json.dumps(row, ensure_ascii=False), day))

    def batch(self, exam, kind, limit):
        with closing(self.connect()) as db:
            return [json.loads(row[0]) for row in db.execute(
                """SELECT body FROM questions WHERE exam=? AND (?='all' OR kind=?)
                   ORDER BY random() LIMIT ?""", (exam, kind, kind, limit))]

    def existing(self):
        with closing(self.connect()) as db:
            return {row[0] for row in db.execute("SELECT id FROM questions")}


def daily_questions(bank, dictionaries, day):
    with closing(bank.connect()) as db:
        if db.execute("SELECT 1 FROM updates WHERE day=?", (day,)).fetchone():
            return
    existing = bank.existing()
    rows = []
    for exam in EXAMS:
        words = json.loads((Path(dictionaries) / f"{exam}.json").read_text(encoding="utf8"))
        words = {w["word"].strip().lower(): w["translation"].strip() for w in words
                 if re.fullmatch(r"[A-Za-z][A-Za-z '-]{0,49}", w["word"]) and 0 < len(w["translation"].strip()) <= 300}
        rng = random.Random(day + exam)
        ordered = list(words)
        rng.shuffle(ordered)
        for kind in ("dictation", "vocabulary"):
            count = 0
            for word in ordered:
                key = f"daily-{exam}-{kind}-" + hashlib.sha256(word.encode()).hexdigest()[:24]
                if key in existing:
                    continue
                choices = []
                if kind == "vocabulary":
                    options = sorted(set(words.values()) - {words[word]})
                    choices = rng.sample(options, 3) + [words[word]]
                    rng.shuffle(choices)
                rows.append(dict(id=key, exam=exam, kind=kind, speech=word, choices=choices,
                                 prompt="听音频，写出英文。" if not choices else f"{word} 的中文释义是什么？",
                                 answer=word if not choices else words[word], explanation=f"{word} — {words[word]}"))
                count += 1
                if count == 8:
                    break
        rows.extend(scenario_questions(exam, rng))
    bank.save(rows, day, daily=True)


def scenario_questions(exam, rng):
    places = ["the library", "the sports hall", "the student centre", "the science building",
              "the music room", "the city museum", "the language centre", "the theatre"]
    old, new, third = rng.sample(places, 3)
    activity = rng.choice(["workshop", "seminar", "tour", "training session", "briefing"])
    start = rng.randint(9, 16)
    delay = rng.choice([15, 25, 35, 45])
    passage = (f"The {activity} was due to start at {start}:00 in {old}. "
               f"It will now take place in {new}, {delay} minutes later than planned.")
    def make(kind, prompt, answer, explanation, choices, speech):
        digest = hashlib.sha256((exam + kind + prompt + speech).encode()).hexdigest()[:24]
        rng.shuffle(choices)
        return dict(id=f"daily-{exam}-{kind}-{digest}", exam=exam, kind=kind, prompt=prompt,
                    answer=answer, explanation=explanation, choices=choices, speech=speech)
    return [
        make("listening", "听通知，选择活动最终举办的地点。", new,
             f"原定地点是 {old}，现在改到 {new}。", [old, new, third], passage),
        make("reading", passage + " When will the session actually start?", f"{start}:{delay}",
             f"原定 {start}:00，推迟 {delay} 分钟。", [f"{start}:00", f"{start}:{delay}", f"{start+1}:00"], ""),
        make("cloze", passage + " Complete the note: The event starts ___ minutes late. Write the number in words.",
             {15: "fifteen", 25: "twenty-five", 35: "thirty-five", 45: "forty-five"}[delay],
             f"later than planned 表示比原计划晚，延误时间为 {delay} 分钟。", [], passage),
    ]
