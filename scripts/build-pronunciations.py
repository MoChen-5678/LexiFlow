"""Regenerate the Android offline pack with eSpeak NG and libmp3lame on Linux."""
import ctypes as c
from ctypes.util import find_library
import io
import json
import subprocess
import sys
import wave
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

assets, output = map(Path, sys.argv[1:])
texts = set()
for path in sorted((assets / "dicts").glob("*.json")):
    if path.name == "vocabulary-stats.json":
        continue
    for row in json.loads(path.read_text(encoding="utf8")):
        texts.add(row["word"].strip().lower())
for row in json.loads((assets / "exam_questions.json").read_text(encoding="utf8")):
    if row.get("speech"):
        texts.add(row["speech"].strip().lower())

lame = c.CDLL(find_library("mp3lame"))
handle = c.c_void_p
lame.lame_init.restype = handle
for name in ("lame_set_num_channels", "lame_set_in_samplerate", "lame_set_brate", "lame_set_bWriteVbrTag"):
    getattr(lame, name).argtypes = [handle, c.c_int]
lame.lame_init_params.argtypes = lame.lame_close.argtypes = [handle]
lame.lame_encode_buffer.argtypes = [handle, c.c_void_p, c.c_void_p, c.c_int, c.c_void_p, c.c_int]
lame.lame_encode_flush.argtypes = [handle, c.c_void_p, c.c_int]


def encode(text, voice):
    wav = subprocess.run(["espeak-ng", "-z", "-s", "155", "-v", voice, "--stdout", "--stdin"],
                         input=text.encode(), stdout=subprocess.PIPE, check=True).stdout
    with wave.open(io.BytesIO(wav)) as stream:
        assert stream.getnchannels() == 1 and stream.getsampwidth() == 2
        pcm = stream.readframes(stream.getnframes())
        hz = stream.getframerate()
    encoder = lame.lame_init()
    try:
        lame.lame_set_num_channels(encoder, 1)
        lame.lame_set_in_samplerate(encoder, hz)
        lame.lame_set_brate(encoder, 32)
        lame.lame_set_bWriteVbrTag(encoder, 0)
        if lame.lame_init_params(encoder) < 0:
            raise RuntimeError("MP3 encoder initialization failed")
        buffer = c.create_string_buffer(len(pcm) + 8192)
        size = lame.lame_encode_buffer(encoder, pcm, pcm, len(pcm) // 2, buffer, len(buffer))
        if size < 0:
            raise RuntimeError("MP3 encoding failed")
        data = buffer.raw[:size]
        tail = lame.lame_encode_flush(encoder, buffer, len(buffer))
        if tail < 0:
            raise RuntimeError("MP3 flush failed")
        return data + buffer.raw[:tail]
    finally:
        lame.lame_close(encoder)


output.mkdir(parents=True, exist_ok=True)
words = sorted(texts - {""})
for accent, voice in [("US", "en-us"), ("UK", "en-gb")]:
    index = {}
    pack = output / f"{accent}.lfpack"
    temp = pack.with_suffix(".tmp")
    with temp.open("wb") as out, ThreadPoolExecutor(max_workers=4) as pool:
        for word, data in zip(words, pool.map(lambda w: encode(w, voice), words)):
            index[word] = [out.tell(), len(data)]
            out.write(data)
    temp.replace(pack)
    (output / f"{accent}.json").write_text(json.dumps(index, separators=(",", ":")), encoding="utf8")
    print(f"{accent}: {len(index)} clips, {pack.stat().st_size} bytes", flush=True)
