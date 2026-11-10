import json
import sqlite3
import unittest
from frequency import archive_frequencies, apply_editions


class FrequencyTest(unittest.TestCase):
    def test_shared_passage_is_counted_once_per_paper_and_listening_is_excluded(self):
        with sqlite3.connect(":memory:") as db:
            db.execute("CREATE TABLE past_items(id TEXT,exam TEXT,body TEXT)")
            for i, paper in enumerate(["2024_06_1", "2024_06_1", "2025_06_1"]):
                row = dict(origin="past", kind="reading", paper=paper, context="Apple apple pear.",
                           prompt="Question", choices=["A. Fruit", "B. Pear"])
                db.execute("INSERT INTO past_items VALUES(?,?,?)", (str(i), "cet4", json.dumps(row)))
            row = dict(origin="past", kind="listening", context="pear " * 50)
            db.execute("INSERT INTO past_items VALUES('listening','cet4',?)", (json.dumps(row),))
            result = archive_frequencies(db)
            self.assertEqual(result["cet4"]["counts"]["apple"], 4)
            self.assertEqual(result["cet4"]["counts"]["pear"], 4)
            self.assertNotIn("a", result["cet4"]["counts"])
            books = {"cet4": [dict(word="apple"), dict(word="unseen", bookIds="cet4,cet4_high")]}
            apply_editions(books, result)
            self.assertEqual(books["cet4"][0]["bookIds"], "cet4,cet4_high")
            self.assertEqual(books["cet4"][1]["bookIds"], "cet4")


if __name__ == "__main__": unittest.main()
