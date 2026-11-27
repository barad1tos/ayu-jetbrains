#!/usr/bin/env python3
"""Export suite-level Gradle test timings without testcase details or logs."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path
from typing import TypedDict
from xml.etree.ElementTree import ParseError

from defusedxml.ElementTree import parse as parse_xml

TEST_TASKS = ("integrationTest", "test")
SUITE_NAME = re.compile(r"[\w$]+(?:\.[\w$]+)*")


class SuiteTiming(TypedDict):
    task: str
    suite: str
    seconds: float
    tests: int
    failures: int
    errors: int
    skipped: int


def read_suite_timing(task: str, report: Path) -> SuiteTiming:
    suite = parse_xml(report, forbid_dtd=True).getroot()
    if suite is None or suite.tag != "testsuite":
        raise ValueError("Invalid test suite element")
    name = suite.attrib["name"]
    seconds = float(suite.attrib["time"])
    if SUITE_NAME.fullmatch(name) is None:
        raise ValueError("Invalid test suite name")
    if not math.isfinite(seconds) or seconds < 0:
        raise ValueError("Invalid suite duration")

    tests = int(suite.attrib["tests"])
    failures = int(suite.attrib["failures"])
    errors = int(suite.attrib["errors"])
    skipped = int(suite.attrib["skipped"])
    if min(tests, failures, errors, skipped) < 0:
        raise ValueError("Negative suite counter")

    return {
        "task": task,
        "suite": name,
        "seconds": seconds,
        "tests": tests,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    results_dir: Path = args.results_dir
    output: Path = args.output

    timings: list[SuiteTiming] = []
    for task in TEST_TASKS:
        try:
            reports = list((results_dir / task).glob("TEST-*.xml"))
            if not reports:
                raise ValueError("No suite reports")
            timings.extend(read_suite_timing(task, report) for report in reports)
        except (ParseError, KeyError, OSError, ValueError):
            print(f"Could not summarize {task} JUnit reports.", file=sys.stderr)
            return 1

    try:
        output.parent.mkdir(parents=True, exist_ok=True)
        timings.sort(key=lambda timing: (timing["task"], timing["suite"]))
        output.write_text(json.dumps(timings, indent=2) + "\n", encoding="utf-8")
    except OSError:
        print("Could not write the test timing report.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
