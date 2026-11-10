import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from vocabulary import Vocabulary, FILES, normalize


class VocabularyTest(unittest.TestCase):
    def test_source_normalization_deduplicates_and_skips_missing_translation(self):
        group = [dict(name="word" + str(i), trans=["meaning"], usphone="w") for i in range(100)]
        group += [dict(name="broken", trans=[None]), dict(name="中文", trans=["bad"])]
        rows = normalize("cet4", [group, group])
        self.assertEqual(len(rows), 100)
        self.assertTrue(all(row["bookIds"] == "cet4" for row in rows))

    def test_publish_is_atomic_and_last_good_snapshot_survives_restart(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            for book in FILES:
                (root / (book + ".json")).write_text(json.dumps([dict(word="test", bookIds=book)]))
            vocabulary = Vocabulary(str(root / "test.sqlite3"), root)
            before = vocabulary.manifest()
            entry = before["books"]["cet4"]
            self.assertEqual(hashlib.sha256(vocabulary.book("cet4", entry["version"])).hexdigest(), entry["version"])
            rows = [dict(name="word" + str(i), trans=["meaning"]) for i in range(100)]
            with patch("vocabulary.head", return_value="a" * 40), patch("vocabulary.read_json", return_value=rows):
                vocabulary.sync()
            self.assertEqual(vocabulary.manifest()["books"]["cet4"]["count"], 100)
            version = vocabulary.manifest()["books"]["cet4"]["version"]
            with self.assertRaises(ValueError):
                vocabulary.book("cet4", entry["version"])
            with patch("vocabulary.head", return_value="b" * 40), patch("vocabulary.read_json", side_effect=OSError("offline")):
                with self.assertRaises(OSError): vocabulary.sync()
            restarted = Vocabulary(str(root / "test.sqlite3"), root)
            self.assertEqual(restarted.manifest()["revision"], "a" * 40)
            self.assertEqual(restarted.manifest()["state"], "failed")
            self.assertEqual(len(json.loads(restarted.book("cet4", version))), 100)


if __name__ == "__main__": unittest.main()
