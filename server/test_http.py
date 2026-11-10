"""Run against a running container: python3 server/test_http.py"""
from array import array
import io
import json
import os
import unittest
import wave
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen

BASE = os.environ.get("TEST_API", "http://127.0.0.1:5000")


class ApiTest(unittest.TestCase):
    def get(self, path):
        with urlopen(BASE + path, timeout=15) as response:
            return response.read()

    def test_exam_filters(self):
        for exam in ("cet4", "cet6", "ielts"):
            for kind in ("reading", "listening", "cloze", "vocabulary", "dictation"):
                url = "/api/questions?" + urlencode(dict(exam=exam, kind=kind, limit=3))
                items = json.loads(self.get(url))["questions"]
                self.assertTrue(1 <= len(items) <= 3)
                self.assertEqual(len(items), len({x["id"] for x in items}))
                self.assertTrue(all((x["exam"], x["kind"]) == (exam, kind) for x in items))

    def test_audio_is_playable_pcm(self):
        import hashlib
        voices = {}
        for accent in ("US", "UK"):
            for gender in ("male", "female"):
                sample = self.get("/api/audio?" + urlencode(dict(text="Meet at 3:30. The fee is 24.95 pounds.",
                                                                 accent=accent, gender=gender)))
                voices[(accent, gender)] = hashlib.sha256(sample).hexdigest()
            self.assertNotEqual(voices[(accent, "male")], voices[(accent, "female")])
        for accent in ("US", "UK"):
            data = self.get("/api/audio?" + urlencode(dict(text="Please book a room for Thursday.",
                                                           accent=accent, speed="1.1")))
            with wave.open(io.BytesIO(data)) as wav:
                frames = wav.readframes(wav.getnframes())
                self.assertEqual(len(frames), wav.getnframes() * wav.getsampwidth() * wav.getnchannels())
                self.assertGreater(wav.getnframes(), wav.getframerate())
                samples = array("h", frames)
                self.assertGreater(max(samples), 100)
                self.assertLess(min(samples), -100)

    def test_api_only(self):
        for path, status in [("/", 404), ("/api/questions?exam=other", 400),
                             ("/api/questions?exam=cet4&limit=-1", 400), ("/api/audio?gender=unknown&text=hello", 400), ("/api/audio?speed=nan", 400)]:
            with self.assertRaises(HTTPError) as caught:
                self.get(path)
            self.assertEqual(caught.exception.code, status)
        self.assertEqual(json.loads(self.get("/health"))["status"], "ready")

    def test_vocabulary_versions_and_natural_provider(self):
        import hashlib
        manifest = json.loads(self.get("/api/vocabulary"))
        self.assertIn(manifest["state"], ("ok", "checking", "bundled", "failed"))
        for book, entry in manifest["books"].items():
            body = self.get("/api/vocabulary/" + book + "?version=" + entry["version"])
            self.assertEqual(hashlib.sha256(body).hexdigest(), entry["version"])
            self.assertEqual(len(json.loads(body)), entry["count"])
            self.assertGreater(entry["count"], 1500)
            rows = json.loads(body)
            self.assertEqual(entry["high_count"], sum(book + "_high" in row["bookIds"].split(",") for row in rows))
            if book in ("cet4", "cet6", "ielts"):
                self.assertGreater(entry["high_count"], 500)
                self.assertLessEqual(entry["high_count"], 1500)
                self.assertIn(book, manifest["frequency"])
            else:
                self.assertEqual(entry["high_count"], 0)
        with self.assertRaises(HTTPError) as caught:
            self.get("/api/vocabulary/cet4?version=stale")
        self.assertEqual(caught.exception.code, 409)
        with urlopen(BASE + "/api/audio?text=ability", timeout=20) as response:
            self.assertIn(response.headers["X-Speech-Engine"], ("kokoro", "tencent"))


if __name__ == "__main__":
    unittest.main()
