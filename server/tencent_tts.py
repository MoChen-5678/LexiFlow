"""Tencent Cloud TextToVoice with server-only TC3 credentials."""
import base64
import csv
import hashlib
import hmac
import json
import os
import time
import uuid
from datetime import datetime, timezone
from urllib.request import Request, urlopen

HOST = "tts.tencentcloudapi.com"


def credentials():
    path = os.environ.get("TENCENT_CREDENTIALS_FILE", "")
    if path:
        with open(path, encoding="utf-8-sig", newline="") as stream:
            row = next(csv.DictReader(stream))
        return row["SecretId"].strip(), row["SecretKey"].strip()
    return os.environ.get("TENCENT_SECRET_ID", ""), os.environ.get("TENCENT_SECRET_KEY", "")


def signed_request(payload, secret_id, secret_key, timestamp, host=HOST):
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
    date = datetime.fromtimestamp(timestamp, timezone.utc).strftime("%Y-%m-%d")
    scope = f"{date}/tts/tc3_request"
    canonical = "POST\n/\n\ncontent-type:application/json; charset=utf-8\nhost:" + host + "\n\ncontent-type;host\n" + hashlib.sha256(body).hexdigest()
    signing = f"TC3-HMAC-SHA256\n{timestamp}\n{scope}\n" + hashlib.sha256(canonical.encode()).hexdigest()
    def mac(key, data):
        return hmac.new(key, data.encode(), hashlib.sha256).digest()
    key = mac(mac(mac(("TC3" + secret_key).encode(), date), "tts"), "tc3_request")
    signature = hmac.new(key, signing.encode(), hashlib.sha256).hexdigest()
    headers = {"Content-Type": "application/json; charset=utf-8", "Host": host,
               "X-TC-Action": "TextToVoice", "X-TC-Version": "2019-08-23", "X-TC-Timestamp": str(timestamp),
               "Authorization": f"TC3-HMAC-SHA256 Credential={secret_id}/{scope}, SignedHeaders=content-type;host, Signature={signature}"}
    return Request("https://" + host, data=body, headers=headers)


def synthesize(text, voice, secret_id, secret_key):
    if not 0 < len(text) <= 500:
        raise ValueError("Tencent speech requires 1..500 characters")
    payload = dict(Text=text, SessionId=str(uuid.uuid4()), VoiceType=voice,
                   PrimaryLanguage=2, ModelType=1, SampleRate=24000, Codec="mp3", Speed=0)
    host = os.environ.get("TENCENT_TTS_HOST", HOST)
    if host not in (HOST, "tts.ap-guangzhou.tencentcloudapi.com", "tts.ap-shanghai.tencentcloudapi.com", "tts.ap-hongkong.tencentcloudapi.com"):
        raise ValueError("invalid Tencent endpoint")
    request = signed_request(payload, secret_id, secret_key, int(time.time()), host)
    with urlopen(request, timeout=15) as response:
        result = json.loads(response.read(4_000_001))["Response"]
    if "Error" in result:
        # Codes are safe to log; provider messages may contain request details.
        raise RuntimeError(result["Error"]["Code"])
    audio = base64.b64decode(result["Audio"], validate=True)
    if len(audio) < 100 or len(audio) > 3_000_000:
        raise ValueError("invalid Tencent audio")
    return audio
