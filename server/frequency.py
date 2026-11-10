"""Observed frequencies in the local archive, never inferred from general English lists."""
import json
import re
from collections import Counter

METHOD = "Exact case-insensitive words/phrases; repeated passage once per paper; >=2 occurrences; top 1500 per book; no stemming. Listening without transcripts excluded."
TOKEN = re.compile(r"[a-z]+(?:['’][a-z]+)?", re.I)


def tokens(text):
    text = re.sub(r"(?m)^\s*[A-Z][.)]\s+", "", text)
    if re.fullmatch(r"[A-Z]", text.strip()):
        return ()
    return tuple(TOKEN.findall(text.lower().replace("’", "'")))


def archive_frequencies(db):
    if not db.execute("SELECT 1 FROM sqlite_master WHERE name='past_items'").fetchone():
        return {}
    result = {}
    for exam in ("cet4", "cet6", "ielts"):
        documents, papers, questions = set(), set(), 0
        for (body,) in db.execute("SELECT body FROM past_items WHERE exam=? ORDER BY id", (exam,)):
            row = json.loads(body)
            if row.get("origin") not in ("past", "recall") or row.get("kind") == "listening":
                continue
            questions += 1
            paper = row.get("paper", "recall")
            papers.add(paper)
            # A passage shared by ten questions contributes once, and distinct papers remain distinct.
            for text in [row.get("context", ""), row.get("prompt", ""), *row.get("choices", [])]:
                normalized = tokens(text)
                if normalized:
                    documents.add((paper, normalized))
        if not documents:
            continue
        counts = Counter()
        for _, words in documents:
            for n in range(1, 6):
                counts.update(" ".join(words[i:i+n]) for i in range(len(words)-n+1))
        # Only repeated forms can enter the high-frequency edition.
        result[exam] = dict(counts={k: v for k, v in sorted(counts.items()) if v >= 2},
            questions=questions, papers=sorted(papers) if exam != "ielts" else [],
            documents=len(documents), scope="speaking recalls" if exam == "ielts" else "reading, cloze and matching")
        if db.execute("SELECT 1 FROM sqlite_master WHERE name='source_state'").fetchone():
            result[exam]["sources"] = [dict(repository=repo, revision=revision) for repo, revision in db.execute(
                "SELECT repo,revision FROM source_state WHERE repo IN (SELECT DISTINCT repo FROM past_items WHERE exam=?) ORDER BY repo", (exam,))]
    return result


def apply_editions(books, frequencies):
    for book, rows in books.items():
        counts = frequencies.get(book, {}).get("counts", {})
        ranked = sorted(((counts.get(" ".join(tokens(row["word"])), 0), row["word"].casefold()) for row in rows),
                        key=lambda item: (-item[0], item[1]))
        selected = {word for count, word in ranked[:1500] if count >= 2}
        for row in rows:
            row["bookIds"] = book + ("," + book + "_high" if row["word"].casefold() in selected else "")
    return books
