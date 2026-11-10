import io
import os
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path
from speech import Speech
from tencent_tts import signed_request, synthesize


class SpeechTest(unittest.TestCase):
    def test_tencent_is_default_and_signed_requests_use_cloud_and_cache(self):
        with tempfile.TemporaryDirectory() as root, patch.dict(os.environ, {}, clear=True):
            engine = Speech(str(Path(root) / "db.sqlite3"), cache=Path(root) / "cache")
            self.assertEqual(engine.status()["provider"], "tencent")
            self.assertEqual(engine.status()["voices"]["US"], {"female": "403006", "male": "403007"})
            with patch("speech.credentials", return_value=("id", "secret")), \
                 patch("speech.cloud_synthesize", return_value=b"MP3" * 100) as cloud, \
                 patch.object(engine, "local") as local:
                first = engine.audio("At 9:30", "US", "female", cloud_allowed=True)
                self.assertEqual(first, engine.audio("At 9:30", "US", "female", cloud_allowed=True))
                self.assertEqual(first[1:], ("audio/mpeg", "tencent"))
                cloud.assert_called_once_with("At 9:30", 403006, "id", "secret")
                engine.audio("At 9:30", "US", "male", cloud_allowed=True)
                self.assertEqual(cloud.call_args.args[1], 403007)
                local.assert_not_called()

    def test_cache_and_cloud_failure_use_local_neural_voice(self):
        with tempfile.TemporaryDirectory() as root:
            engine = Speech(str(Path(root) / "db.sqlite3"), cache=Path(root) / "cache")
            with patch.object(engine, "local", return_value=(b"valid-wave" * 100, "audio/wav", "kokoro")) as local:
                first = engine.audio("hello", "US", "female")
                self.assertEqual(first, engine.audio("hello", "US", "female"))
                self.assertEqual(local.call_count, 1)
                engine.provider = "tencent"
                with patch("speech.credentials", return_value=("id", "secret")), patch("speech.cloud_synthesize", side_effect=RuntimeError("offline")):
                    result = engine.audio("new sentence", "US", "male", cloud_allowed=True)
                self.assertEqual(result[2], "kokoro")
                with patch("speech.cloud_synthesize") as cloud:
                    engine.audio("UK sentence", "UK", "male", cloud_allowed=True)
                    engine.audio("unsigned sentence", "US", "male", cloud_allowed=False)
                    cloud.assert_not_called()

    def test_tc3_signs_body_and_uses_english_language(self):
        a = signed_request({"Text": "hello"}, "id", "secret", 1551113065)
        b = signed_request({"Text": "goodbye"}, "id", "secret", 1551113065)
        self.assertNotEqual(a.headers["Authorization"], b.headers["Authorization"])
        self.assertIn("/tts/tc3_request", a.headers["Authorization"])
        self.assertNotIn("secret", a.headers["Authorization"])
        import json, base64
        fake = io.BytesIO(json.dumps({"Response": {"Audio": base64.b64encode(b"MP3" * 100).decode()}}).encode())
        with patch("tencent_tts.urlopen", return_value=fake) as network:
            self.assertGreater(len(synthesize("At 9:30", 403006, "id", "secret")), 100)
        body = json.loads(network.call_args.args[0].data)
        self.assertEqual(body["PrimaryLanguage"], 2)
        self.assertEqual(body["VoiceType"], 403006)
        self.assertEqual(body["Speed"], 0)


if __name__ == "__main__": unittest.main()
