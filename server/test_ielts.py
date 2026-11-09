import json
import unittest
from pathlib import Path
from ielts import load_sets, flat_questions

ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets"


class IeltsTest(unittest.TestCase):
    def test_every_answer_has_evidence_and_complete_material(self):
        tests = load_sets(ASSETS / "ielts_sets.json")
        self.assertEqual(len(tests), 4)
        self.assertEqual(sum(len(t["questions"]) for t in tests), 40)
        for test in tests:
            self.assertFalse(any("\u4e00" <= c <= "\u9fff" for c in test["speech"]))
            self.assertGreater(len(test["speech"].split()), 250)
        questions = flat_questions(tests, "reading")
        self.assertEqual(len(questions), 20)
        self.assertTrue(all(q["context"] for q in questions))

    def test_word_contexts_contain_the_exact_target(self):
        import re
        examples = json.loads((ASSETS / "word_examples.json").read_text(encoding="utf8"))
        self.assertGreater(len(examples), 4000)
        for word, sample in examples.items():
            self.assertRegex(sample["sentence"].lower(), r"(?<![a-z])" + re.escape(word) + r"(?![a-z])")
            self.assertTrue(sample["source"])
