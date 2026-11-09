import hashlib
import hmac
import io
import json
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from past import Archive, CET, SOURCES, parse_paper, recall_rows


class PastTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.archive = Archive(str(Path(self.temp.name) / "test.db"))

    def test_stable_source_snapshots(self):
        paper = dict(level="cet4", id="2025_06_1", title="June 2025", hasAudio=False,
            passages={"one": {"text": "Test passage"}}, questions=[dict(part="reading", kind="mcq",
            no=46, passage="one", stem="Question?", options={"A": "Yes", "B": "No"}, answer="B")])
        rows = parse_paper(paper, CET, "a" * 40)
        self.assertEqual(rows[0]["answer"], "B. No")
        with patch("past.subprocess.run", return_value=SimpleNamespace(stdout="a" * 40)), patch("past.cet_rows", return_value=rows) as fetch:
            self.archive.sync(CET)
            self.archive.sync(CET)
            fetch.assert_called_once()
        with patch("past.subprocess.run", return_value=SimpleNamespace(stdout="b" * 40)), patch("past.cet_rows", side_effect=ValueError("invalid upstream")):
            self.archive.sync(CET)
        self.assertEqual(rows, self.archive.questions("cet4", "all", 10))
        self.assertEqual(self.archive.status()[0]["revision"], "a" * 40)
        with patch("past.subprocess.run", return_value=SimpleNamespace(stdout="b" * 40)), patch("past.cet_rows", return_value=[dict(rows[0], answer="A. Yes")]):
            self.archive.sync(CET)
        self.assertEqual(self.archive.questions("cet4", "all", 10)[0]["answer"], "A. Yes")

    def test_recall_requires_dated_attribution(self):
        source = dict(id="one", period="2025-06", region="UK", url="https://example.org/recall",
                      phase="historical_or_unlabelled", status="test_taker_recall")
        data = {"sources": {"one": source, "two": dict(source, status="prediction")},
                "topics": [{"questions": [dict(id="first", part="Part 1", text="Where do you study?", sourceRefs=["one"]),
                                          dict(id="second", part="Part 2", text="Unverified", sourceRefs=["two"])]}]}
        result = recall_rows(data)
        self.assertEqual([row["id"] for row in result], ["recall-first"])
        self.assertEqual(result[0]["answer"], "")
        self.assertIn("2025-06", result[0]["label"])

    def test_signed_access_and_push(self):
        stamp = str(int(time.time()))
        path = "/api/questions"
        query = "exam=cet4&origin=past"
        signature = hmac.new(self.archive.key.encode(), f"{stamp}\n{path}?{query}".encode(), hashlib.sha256).hexdigest()
        env = dict(PATH_INFO=path, QUERY_STRING=query, HTTP_X_STUDY_TIME=stamp, HTTP_X_STUDY_SIGNATURE=signature)
        self.assertTrue(self.archive.authorized(env))
        self.assertFalse(self.archive.authorized(dict(env, QUERY_STRING="exam=ielts")))
        body = json.dumps({"repository": {"full_name": CET}, "ref": "refs/heads/" + SOURCES[CET]}).encode()
        with patch.dict("os.environ", GITHUB_WEBHOOK_SECRET="test-only"):
            env = {"CONTENT_LENGTH": str(len(body)), "wsgi.input": io.BytesIO(body), "HTTP_X_GITHUB_EVENT":"push"}
            self.assertEqual(self.archive.webhook(env), "403 Forbidden")
            env["wsgi.input"] = io.BytesIO(body)
            env["HTTP_X_HUB_SIGNATURE_256"] = "sha256=" + hmac.new(b"test-only", body, hashlib.sha256).hexdigest()
            self.assertEqual(self.archive.webhook(env), "202 Accepted")
            self.assertTrue(self.archive.wake.is_set())
