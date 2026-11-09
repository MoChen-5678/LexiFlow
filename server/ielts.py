"""Original grouped IELTS skill practice, shared with the offline Android asset."""
import json
from pathlib import Path


def load_sets(path):
    sets = json.loads(Path(path).read_text(encoding="utf8"))
    ids = set()
    for test in sets:
        if test["id"] in ids or test["skill"] not in ("reading", "listening"):
            raise ValueError("invalid IELTS set")
        ids.add(test["id"])
        text = test["passage"] or test["script"]
        if len(text.split()) < 250 or len(test["questions"]) < 8:
            raise ValueError("IELTS practice material is incomplete")
        numbers = set()
        for question in test["questions"]:
            if question["id"] in numbers or not question["answers"]:
                raise ValueError("invalid question numbering or answer")
            numbers.add(question["id"])
            if question["evidence"] not in text:
                raise ValueError("answer evidence missing from passage")
            if question["choices"] and any(a not in question["choices"] for a in question["answers"]):
                raise ValueError("answer does not match choice")
            if question["wordLimit"] and any(len(a.split()) > question["wordLimit"] for a in question["answers"]):
                raise ValueError("answer exceeds word limit")
            if question["type"] == "completion" and not ("___" in question["prompt"]):
                raise ValueError("completion prompt has no blank")
    return sets


def flat_questions(sets, kind):
    rows = []
    for test in sets:
        for q in test["questions"]:
            if kind not in ("all", test["skill"], "cloze"):
                continue
            if kind == "cloze" and q["type"] != "completion":
                continue
            rows.append(dict(id=f'{test["id"]}-{q["id"]}', exam="ielts", kind="cloze" if kind == "cloze" else test["skill"],
                prompt=q["prompt"], context=test["passage"], answer=q["answers"][0], choices=q["choices"],
                explanation=q["explanation"], speech=test["speech"] if test["skill"] == "listening" else "",
                setId=test["id"], instructions=q["instruction"]))
    return rows
