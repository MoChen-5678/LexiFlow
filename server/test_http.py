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


if __name__ == "__main__":
    unittest.main()
