"""Regenerate bundled books using the same pinned sources as the daily server job."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
from mirrors import head, read_json, urls
from frequency import apply_editions
from vocabulary import FILES, REPO, normalize, encoded
from urllib.request import urlopen

revision = head(REPO, "master")
sources = {name: read_json(REPO, revision, "public/dicts/" + name + ".json", 8_000_000)
           for name in dict.fromkeys(n for names in FILES.values() for n in names)}
books = {book: normalize(book, [sources[name] for name in names]) for book, names in FILES.items()}
assets = ROOT / "app/src/main/assets"
frequencies = json.loads((assets / "exam_frequency.json").read_text(encoding="utf8"))
apply_editions(books, frequencies)
for book, rows in books.items():
    (assets / "dicts" / (book + ".json")).write_bytes(encoded(rows))
    print(book, len(rows))
(assets / "vocabulary_sources.json").write_bytes(encoded(dict(repository=REPO, revision=revision, license="GPL-3.0", files=FILES)))
for url in urls(REPO, revision, "LICENSE"):
    try:
        license_text = urlopen(url, timeout=15).read(100000)
        if b"GNU GENERAL PUBLIC LICENSE" not in license_text:
            raise ValueError("unexpected vocabulary license")
        (assets / "vocabulary_license.txt").write_bytes(license_text)
        break
    except (OSError, ValueError):
        continue
else:
    raise OSError("Could not retrieve source license")
