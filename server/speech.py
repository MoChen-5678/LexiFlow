"""Cached local neural English speech. Cloud credentials never leave this process."""
import hashlib
import io
import logging
import os
import sqlite3
import threading
import time
from contextlib import closing
from pathlib import Path
from tencent_tts import credentials, synthesize as cloud_synthesize

VOICES = {("US", "female"): "af_heart", ("US", "male"): "am_michael",
          ("UK", "female"): "bf_emma", ("UK", "male"): "bm_george"}


class Speech:
    def __init__(self, db_path, models="/models", cache="/data/audio"):
        self.db_path = db_path
        self.models = Path(models)
        self.cache = Path(cache)
        self.cache.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.cache_lock = threading.RLock()
        self.engine = None
        self.provider = os.environ.get("TTS_PROVIDER", "tencent")
        if self.provider not in ("kokoro", "tencent"):
            raise ValueError("invalid speech provider")
        with closing(sqlite3.connect(db_path)) as db, db:
            db.execute("CREATE TABLE IF NOT EXISTS tts_usage (day TEXT PRIMARY KEY, chars INTEGER)")

    def status(self):
        voices = {accent: dict(values) for accent, values in VOICES_TO_JSON.items()}
        if self.provider == "tencent":
            voices["US"] = {"female": os.environ.get("TENCENT_VOICE_FEMALE", "403006"),
                            "male": os.environ.get("TENCENT_VOICE_MALE", "403007")}
        return dict(provider=self.provider, ready=self.engine is not None,
                    accents=["US", "UK"], voices=voices, fallback_provider="kokoro",
                    cloud_requires_study_code=self.provider == "tencent")

    def warm(self):
        try:
            self.local("Welcome to LexiFlow.", "US", "female", 1)
        except Exception:
            logging.exception("Neural speech initialization failed")

    def local(self, text, accent, gender, speed):
        from kokoro_onnx import Kokoro
        import soundfile
        import onnxruntime
        with self.lock:
            if self.engine is None:
                options = onnxruntime.SessionOptions()
                options.intra_op_num_threads = int(os.environ.get("TTS_THREADS", "2"))
                options.inter_op_num_threads = 1
                session = onnxruntime.InferenceSession(str(self.models / "kokoro-v1.0.int8.onnx"),
                    sess_options=options, providers=["CPUExecutionProvider"])
                self.engine = Kokoro.from_session(session, str(self.models / "voices-v1.0.bin"))
            samples, rate = self.engine.create(text, voice=VOICES[(accent, gender)], speed=speed,
                                                lang="en-us" if accent == "US" else "en-gb")
        out = io.BytesIO()
        soundfile.write(out, samples, rate, format="WAV", subtype="PCM_16")
        return out.getvalue(), "audio/wav", "kokoro"

    def audio(self, text, accent, gender, speed=1, cloud_allowed=False):
        with self.cache_lock:
            return self._audio(text, accent, gender, speed, cloud_allowed)

    def _audio(self, text, accent, gender, speed, cloud_allowed):
        cloud = self.provider == "tencent" and cloud_allowed and accent == "US" and len(text) <= 500 and speed == 1
        provider = "tencent" if cloud else "kokoro"
        key = hashlib.sha256(f"v1|{provider}|{accent}|{gender}|{speed}|{text}".encode()).hexdigest()
        path = self.cache / (key + (".mp3" if cloud else ".wav"))
        if path.exists():
            os.utime(path, None)
            return path.read_bytes(), "audio/mpeg" if cloud else "audio/wav", provider
        if cloud:
            day = time.strftime("%Y-%m-%d", time.gmtime())
            with closing(sqlite3.connect(self.db_path, timeout=20)) as db, db:
                db.execute("INSERT OR IGNORE INTO tts_usage VALUES(?,0)", (day,))
                allowed = db.execute("UPDATE tts_usage SET chars=chars+? WHERE day=? AND chars+?<=?",
                    (len(text), day, len(text), int(os.environ.get("TTS_DAILY_CHAR_LIMIT", "20000")))).rowcount
            try:
                if not allowed:
                    raise RuntimeError("daily limit reached")
                sid, secret = credentials()
                if not sid or not secret:
                    raise RuntimeError("credentials missing")
                voice = int(os.environ.get("TENCENT_VOICE_FEMALE" if gender == "female" else "TENCENT_VOICE_MALE",
                                           "403006" if gender == "female" else "403007"))
                result = cloud_synthesize(text, voice, sid, secret), "audio/mpeg", "tencent"
            except Exception:
                logging.warning("Cloud speech unavailable; using local neural voice")
                return self.audio(text, accent, gender, speed, cloud_allowed=False)
        else:
            result = self.local(text, accent, gender, speed)
        # One request owns a slot, but another slot may synthesize the same cache key.
        temp = path.with_name(path.name + "." + str(threading.get_ident()) + ".tmp")
        temp.write_bytes(result[0])
        os.replace(temp, path)
        files = list(self.cache.glob("*.wav")) + list(self.cache.glob("*.mp3"))
        total = sum(p.stat().st_size for p in files)
        if total > 256 * 1024 * 1024:
            for old in sorted(files, key=lambda p: p.stat().st_mtime):
                if old == path:
                    continue
                total -= old.stat().st_size
                old.unlink(missing_ok=True)
                if total <= 200 * 1024 * 1024:
                    break
        return result


VOICES_TO_JSON = {accent: {gender: voice for (a, gender), voice in VOICES.items() if a == accent} for accent in ("US", "UK")}
