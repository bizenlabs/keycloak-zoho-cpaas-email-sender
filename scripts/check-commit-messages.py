#!/usr/bin/env python3
"""Fail unless every commit subject follows Conventional Commits 1.0.0.

    <type>[(scope)][!]: <description>

https://www.conventionalcommits.org/en/v1.0.0/. The types this repository
uses, and what each means here, are in CONTRIBUTING.md.

Usage:

    check-commit-messages.py <revision range>      # e.g. origin/main..HEAD
    check-commit-messages.py --message "<text>"    # one message, e.g. a PR title

Merge commits are skipped: the subject that lands on main is the pull
request's title (squash merge), which CI checks with --message.
"""
import re
import subprocess
import sys

TYPES = ("feat", "fix", "docs", "ci", "test", "refactor", "perf", "build", "chore", "revert")
HEADER = re.compile(
    r"^(?P<type>[a-z]+)"
    r"(?:\((?P<scope>[a-z0-9][a-z0-9/._-]*)\))?"
    r"(?P<breaking>!)?"
    r": (?P<description>\S.*)$"
)


def problems(message):
    """What is wrong with one commit message; empty if nothing."""
    lines = message.strip("\n").split("\n")
    subject = lines[0]
    match = HEADER.match(subject)
    if not match:
        return [f"{subject!r}: not '<type>[(scope)][!]: <description>'"]
    found = []
    if match["type"] not in TYPES:
        found.append(f"{subject!r}: type '{match['type']}' is not one of {', '.join(TYPES)}")
    if len(lines) > 1 and lines[1].strip():
        found.append(f"{subject!r}: leave a blank line between the subject and the body")
    return found


def messages_in(revision_range):
    out = subprocess.run(
        ["git", "log", "--no-merges", "--format=%H%x00%B%x00", revision_range],
        check=True, capture_output=True, text=True,
    ).stdout
    fields = out.split("\0")
    # Pairs of (sha, message); the final field is the trailing newline.
    return [(fields[i].strip(), fields[i + 1]) for i in range(0, len(fields) - 1, 2)]


def main(argv):
    if len(argv) == 3 and argv[1] == "--message":
        checked = [("message", argv[2])]
    elif len(argv) == 2:
        checked = messages_in(argv[1])
    else:
        print(__doc__)
        return 2
    found = [f"{sha[:12]}: {p}" for sha, message in checked for p in problems(message)]
    for problem in found:
        print(f"::error::{problem}")
    if found:
        print("See docs/onboarding.md, 'Commits', and https://www.conventionalcommits.org/en/v1.0.0/")
        return 1
    print(f"{len(checked)} commit message(s) follow Conventional Commits")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
