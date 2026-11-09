#!/usr/bin/env python3
"""Regenerates app/src/main/res/raw/contributors.json from git history.

Run from the repository root:
    python3 tools/contributors/update_contributors.py

Commits are grouped by author name. Bots are left out. Add an entry to
ALIASES when one person has committed under several names.
"""
import json
import re
import subprocess
from pathlib import Path

OUTPUT = Path("app/src/main/res/raw/contributors.json")
EXCLUDED = re.compile(r"\[bot\]$|^Copilot$|^dependabot", re.IGNORECASE)
ALIASES = {
    # "old name": "display name",
}


def main():
    log = subprocess.run(
        ["git", "shortlog", "-sn", "--no-merges", "HEAD"],
        check=True, capture_output=True, text=True,
    ).stdout

    counts = {}
    for line in log.splitlines():
        commits, name = line.strip().split("\t", 1)
        name = ALIASES.get(name, name).strip()
        if not name or EXCLUDED.search(name):
            continue
        counts[name] = counts.get(name, 0) + int(commits)

    contributors = [
        {"name": name, "contributions": commits}
        for name, commits in sorted(counts.items(), key=lambda item: (-item[1], item[0].lower()))
    ]
    OUTPUT.write_text(json.dumps(contributors, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {len(contributors)} contributors to {OUTPUT}")


if __name__ == "__main__":
    main()
