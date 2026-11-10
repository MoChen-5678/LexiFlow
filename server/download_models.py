"""Fetch Kokoro model files through domestic GitHub mirrors."""
import hashlib
import os
from pathlib import Path
from urllib.request import Request, urlopen

FILES = {"kokoro-v1.0.int8.onnx": "6e742170d309016e5891a994e1ce1559c702a2ccd0075e67ef7157974f6406cb",
         "voices-v1.0.bin": "bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d"}
RELEASE = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/"


def download(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    for name, expected in FILES.items():
        target = directory / name
        if target.exists() and (expected is None or hashlib.sha256(target.read_bytes()).hexdigest() == expected):
            continue
        for proxy in ("https://gh-proxy.org/", "https://ghfast.top/"):
            temp = target.with_suffix(target.suffix + ".part")
            try:
                req = Request(proxy + RELEASE + name, headers={"User-Agent": "LexiFlow"})
                with urlopen(req, timeout=30) as response, temp.open("wb") as stream:
                    while True:
                        chunk = response.read(1024 * 1024)
                        if not chunk:
                            break
                        stream.write(chunk)
                digest = hashlib.sha256(temp.read_bytes()).hexdigest()
                if expected and digest != expected:
                    raise ValueError("model checksum mismatch")
                os.replace(temp, target)
                print(name, target.stat().st_size, digest, flush=True)
                break
            except Exception:
                temp.unlink(missing_ok=True)
        else:
            raise OSError("Both domestic mirrors failed for " + name)


if __name__ == "__main__":
    import sys
    download(sys.argv[1] if len(sys.argv) > 1 else "/models")
