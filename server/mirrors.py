"""Public, unauthenticated mirror routes for Git refs, pinned files and original recordings."""
from http.client import HTTPException
import json
import os
import re
import subprocess
from urllib.error import HTTPError
from urllib.request import Request, urlopen

GIT_PROXIES = ("https://gh-proxy.org", "https://ghfast.top")
CDN = "https://cdn.jsdmirror.cn"


def urls(repo, rev, path):
    raw = f"https://raw.githubusercontent.com/{repo}/{rev}/{path}"
    return [f"{CDN}/gh/{repo}@{rev}/{path}",
            *(f"{proxy}/{raw}" for proxy in GIT_PROXIES)]


def head(repo, branch):
    for proxy in GIT_PROXIES:
        try:
            ref = subprocess.run(["git", "ls-remote", "--exit-code",
                                  f"{proxy}/https://github.com/{repo}.git", f"refs/heads/{branch}"],
                                 capture_output=True, text=True, check=True, timeout=18,
                                 env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
            sha = ref.stdout.split()[0]
            if re.fullmatch("[a-f0-9]{40}", sha):
                return sha
        except (subprocess.SubprocessError, IndexError):
            continue
    raise OSError(f"Neither Git mirror returned {repo}@{branch}")


def read_json(repo, rev, path, limit):
    missing = False
    errors = []
    for url in urls(repo, rev, path):
        try:
            with urlopen(Request(url, headers={"User-Agent": "LexiFlow-study"}), timeout=15) as response:
                data = response.read(limit + 1)
            if len(data) > limit:
                raise ValueError("source exceeds size limit")
            return json.loads(data)
        except HTTPError as exc:
            missing |= exc.code == 404
            errors.append(str(exc))
        except (OSError, ValueError, HTTPException) as exc:
            errors.append(str(exc))
    if missing:
        raise FileNotFoundError(f"{repo}/{path}")
    raise OSError(f"{repo}/{path}: " + ", ".join(errors))
