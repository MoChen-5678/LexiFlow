import json
import tempfile
import unittest
from pathlib import Path

from bank import Bank, EXAMS, KINDS, daily_questions

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"


class BankTest(unittest.TestCase):
    def test_updates_are_atomic_and_survive_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "questions.db"
            bank = Bank(path)
            seed = json.loads((ASSETS / "exam_questions.json").read_text(encoding="utf8"))
            bank.save(seed, "2026-01-01")
            original = bank.existing()
            with self.assertRaises(ValueError):
                bank.save([dict(seed[0], id="valid"), dict(seed[1], id="broken", answer="")], "2026-01-02")
            self.assertEqual(original, Bank(path).existing())
            revised = dict(seed[0], explanation="Updated explanation")
            bank.save([revised], "2026-01-02")
            items = bank.batch(revised["exam"], revised["kind"], 30)
            self.assertIn(revised, items)

    def test_daily_publication(self):
        with tempfile.TemporaryDirectory() as folder:
            bank = Bank(Path(folder) / "questions.db")
            daily_questions(bank, ASSETS / "dicts", "2026-01-01")
            initial = bank.existing()
            self.assertEqual(len(initial), 57)
            daily_questions(bank, ASSETS / "dicts", "2026-01-01")
            self.assertEqual(initial, bank.existing())
            daily_questions(bank, ASSETS / "dicts", "2026-01-02")
            self.assertTrue(initial < bank.existing())
            for exam in EXAMS:
                for kind in KINDS:
                    questions = bank.batch(exam, kind, 30)
                    self.assertTrue(questions)
                    for item in questions:
                        self.assertEqual((item["exam"], item["kind"]), (exam, kind))
                        if item["choices"]:
                            self.assertIn(item["answer"], item["choices"])
