#!/usr/bin/env python3
"""Prepare and compare artifacts for the isolated CI test matrix benchmark."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import sys
from collections import Counter
from pathlib import Path
from statistics import median
from typing import cast
from xml.etree.ElementTree import Element, ParseError

from defusedxml.common import DefusedXmlException
from defusedxml.ElementTree import parse

CLASS_NAME = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*")
DIGEST = re.compile(r"[0-9a-f]{64}")


class BenchmarkError(ValueError):
    """An input did not satisfy the benchmark contract."""

    def __init__(self, message: str):
        super().__init__(message)


def read_object(path: Path) -> dict[str, object]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise BenchmarkError("Expected a JSON object.")
    return cast(dict[str, object], value)


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True) + "\n", encoding="utf-8")


def run_scope() -> dict[str, str]:
    scope = {
        "sha": os.environ.get("GITHUB_SHA", ""),
        "run": os.environ.get("GITHUB_RUN_ID", ""),
        "attempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
    }
    if not re.fullmatch(r"[0-9a-f]{40}", scope["sha"]) or not all(
        re.fullmatch(r"[1-9][0-9]*", scope[key]) for key in ("run", "attempt")
    ):
        raise BenchmarkError("Missing or invalid workflow scope.")
    return scope


def digest(value: object) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode("ascii")
    ).hexdigest()


def timing_weights(timing_file: Path) -> dict[str, float]:
    data = json.loads(timing_file.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise BenchmarkError("Expected a list of suite timings.")
    timings: dict[str, float] = {}
    for value in cast(list[object], data):
        if not isinstance(value, dict):
            raise BenchmarkError("Invalid suite timing record.")
        row = cast(dict[str, object], value)
        if row.get("task") != "test":
            continue
        name, seconds = row.get("suite"), row.get("seconds")
        if (
            not isinstance(name, str)
            or not CLASS_NAME.fullmatch(name)
            or name in timings
            or isinstance(seconds, bool)
            or not isinstance(seconds, (float, int))
            or not math.isfinite(seconds)
            or seconds < 0
        ):
            raise BenchmarkError("Invalid or duplicate unit suite timing.")
        timings[name] = seconds

    return timings


def xml_root(path: Path) -> Element:
    # Parsing a document without a root raises ParseError.
    return cast(Element, parse(path).getroot())


def plan(discovery: Path, shard_count: int, output: Path, timings: Path | None) -> None:
    classes: set[str] = set()
    for report in discovery.glob("TEST-*.xml"):
        name = xml_root(report).get("name")
        if not name or not CLASS_NAME.fullmatch(name) or name in classes:
            raise BenchmarkError("Invalid or duplicate discovered test class.")
        classes.add(name)
    # GitHub caps the unit matrix at 256 jobs.
    if not 1 <= shard_count <= min(len(classes), 256):
        raise BenchmarkError("Invalid shard count for the discovered tests.")
    observed = timing_weights(timings) if timings is not None else {}
    known_weights = [observed[name] for name in classes if name in observed]
    fallback = median(known_weights) if known_weights else 1.0

    units: dict[str, list[str]] = {f"unit-{index}": [] for index in range(shard_count)}
    loads = dict.fromkeys(units, 0.0)
    for name in sorted(
        classes,
        key=lambda test_class: (-observed.get(test_class, fallback), test_class),
    ):
        role = min(units, key=lambda worker: (loads[worker], len(units[worker])))
        units[role].append(name)
        loads[role] += observed.get(name, fallback)
    write_json(output, {"scope": run_scope(), "units": units})
    print(json.dumps(list(units), separators=(",", ":")))


def load_plan(path: Path) -> dict[str, list[str]]:
    manifest = read_object(path)
    if manifest.get("scope") != run_scope():
        raise BenchmarkError("Plan scope does not match.")
    raw_units = manifest.get("units")
    if not isinstance(raw_units, dict) or not raw_units:
        raise BenchmarkError("Missing unit groups in the plan.")
    units: dict[str, list[str]] = {}
    assigned: set[str] = set()
    for role, raw_classes in cast(dict[str, object], raw_units).items():
        if (
            not re.fullmatch(r"unit-[0-9]+", role)
            or not isinstance(raw_classes, list)
            or not raw_classes
        ):
            raise BenchmarkError("Invalid unit group in the plan.")
        classes: list[str] = []
        for name in cast(list[object], raw_classes):
            if (
                not isinstance(name, str)
                or not CLASS_NAME.fullmatch(name)
                or name in assigned
            ):
                raise BenchmarkError("Invalid or duplicate class in the plan.")
            assigned.add(name)
            classes.append(name)
        units[role] = classes
    return units


def select(plan_file: Path, role: str) -> None:
    units = load_plan(plan_file)
    if role not in units:
        raise BenchmarkError("Unknown unit group in the plan.")
    print("\n".join(units[role]))


def junit_inventory(build: Path, phase: str) -> Counter[str]:
    counts: Counter[str] = Counter()
    for report in sorted((build / "test-results" / phase).glob("TEST-*.xml")):
        suite = xml_root(report)
        cases = list(suite.iter("testcase"))
        if suite.tag != "testsuite" or len(cases) != int(suite.get("tests", "-1")):
            raise BenchmarkError("Invalid JUnit case count.")
        for case in cases:
            class_name, name = case.get("classname"), case.get("name")
            if not class_name or not name:
                raise BenchmarkError("Missing JUnit test identity.")
            if case.find("failure") is not None or case.find("error") is not None:
                raise BenchmarkError("A JUnit test failed.")
            outcome = "skipped" if case.find("skipped") is not None else "passed"
            counts[digest((phase, class_name, name, outcome))] += 1
    if not counts:
        raise BenchmarkError("No JUnit cases for an expected phase.")
    return counts


def save_inventory(output: Path, role: str, tests: Counter[str]) -> None:
    write_json(
        output / "inventory.json", {"scope": run_scope(), "role": role, "tests": tests}
    )


def load_inventory(directory: Path, role: str) -> Counter[str]:
    manifest = read_object(directory / "inventory.json")
    if manifest.get("scope") != run_scope() or manifest.get("role") != role:
        raise BenchmarkError("Artifact scope or worker does not match.")
    data = manifest.get("tests")
    if not isinstance(data, dict) or not data:
        raise BenchmarkError("Artifact has no JUnit cases.")
    counts: Counter[str] = Counter()
    for signature, count in cast(dict[str, object], data).items():
        if not DIGEST.fullmatch(signature) or type(count) is not int or count < 1:
            raise BenchmarkError("Invalid hashed JUnit case.")
        counts[signature] = count
    return counts


def capture(role: str, build: Path, output: Path) -> None:
    if role not in ("baseline", "integration") and not re.fullmatch(
        r"unit-[0-9]+", role
    ):
        raise BenchmarkError("Unknown benchmark worker.")
    if role == "baseline":
        tests = junit_inventory(build, "test") + junit_inventory(
            build, "integrationTest"
        )
        coverage(build / "reports/kover/report.xml", output)
    else:
        phase = "integrationTest" if role == "integration" else "test"
        tests = junit_inventory(build, phase)
        source = build / "kover" / "bin-reports" / f"{phase}.ic"
        if source.stat().st_size == 0:
            raise BenchmarkError("Empty worker coverage binary.")
        output.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, output / "coverage.ic")
    save_inventory(output, role, tests)


def collect(plan_file: Path, artifacts: Path, output: Path) -> None:
    workers = (*load_plan(plan_file), "integration")
    prefix = f"benchmark-worker-{run_scope()['attempt']}-"
    expected = {prefix + role for role in workers}
    if {child.name for child in artifacts.iterdir()} != expected:
        raise BenchmarkError("Unexpected or missing benchmark workers.")

    combined: Counter[str] = Counter()
    binaries: list[tuple[str, Path]] = []
    for role in workers:
        group = artifacts / (prefix + role)
        tests = load_inventory(group, role)
        if combined.keys() & tests.keys():
            raise BenchmarkError("Found a duplicate JUnit case across workers.")
        combined.update(tests)
        binary = group / "coverage.ic"
        if binary.stat().st_size == 0:
            raise BenchmarkError("Empty worker coverage binary.")
        binaries.append((role, binary))

    output_dir = output / "coverage"
    output_dir.mkdir(parents=True, exist_ok=False)
    for role, binary in binaries:
        shutil.copyfile(binary, output_dir / f"{role}.ic")
    save_inventory(output, "candidate", combined)


def coverage(report: Path, output: Path) -> None:
    root = xml_root(report)
    lines: dict[tuple[str, str, int], bool] = {}
    for package in root.iter("package"):
        for source in package.findall("sourcefile"):
            for line in source.findall("line"):
                key = (
                    package.attrib["name"],
                    source.attrib["name"],
                    int(line.attrib["nr"]),
                )
                if key in lines:
                    raise BenchmarkError("Duplicate Kover source line.")
                lines[key] = int(line.attrib["ci"]) > 0
    total = root.find("counter[@type='LINE']")
    if total is None or not lines:
        raise BenchmarkError("Kover report lacks source line coverage.")
    hits, missed = int(total.attrib["covered"]), int(total.attrib["missed"])
    if min(hits, missed) < 0 or not hits + missed:
        raise BenchmarkError("Invalid Kover line totals.")
    write_json(
        output / "coverage.json",
        {"digest": digest(sorted(lines.items())), "covered": hits, "missed": missed},
    )


def compare(baseline: Path, candidate: Path) -> None:
    reference_tests = load_inventory(baseline, "baseline")
    candidate_tests = load_inventory(candidate, "candidate")
    if reference_tests != candidate_tests:
        missing = sum((reference_tests - candidate_tests).values())
        extra = sum((candidate_tests - reference_tests).values())
        raise BenchmarkError(
            f"JUnit inventories differ: {missing} missing, {extra} extra."
        )
    reference = read_object(baseline / "coverage.json")
    alternate = read_object(candidate / "coverage.json")
    signature = reference.get("digest")
    if (
        not isinstance(signature, str)
        or not DIGEST.fullmatch(signature)
        or reference != alternate
    ):
        raise BenchmarkError("Kover line coverage differs.")
    print(
        f"Matched {sum(reference_tests.values())} JUnit cases and exact Kover line coverage "
        f"({reference['covered']} covered, {reference['missed']} missed)."
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    plan_parser = commands.add_parser("plan")
    plan_parser.add_argument("discovery", type=Path)
    plan_parser.add_argument("shards", type=int)
    plan_parser.add_argument("output", type=Path)
    plan_parser.add_argument("--timings", type=Path)
    select_parser = commands.add_parser("select")
    select_parser.add_argument("plan", type=Path)
    select_parser.add_argument("role")
    capture_parser = commands.add_parser("capture")
    capture_parser.add_argument("role")
    capture_parser.add_argument("build", type=Path)
    capture_parser.add_argument("output", type=Path)
    collect_parser = commands.add_parser("collect")
    collect_parser.add_argument("plan", type=Path)
    collect_parser.add_argument("artifacts", type=Path)
    collect_parser.add_argument("output", type=Path)
    coverage_parser = commands.add_parser("coverage")
    coverage_parser.add_argument("report", type=Path)
    coverage_parser.add_argument("output", type=Path)
    compare_parser = commands.add_parser("compare")
    compare_parser.add_argument("baseline", type=Path)
    compare_parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    try:
        match args.command:
            case "plan":
                plan(args.discovery, args.shards, args.output, args.timings)
            case "select":
                select(args.plan, args.role)
            case "capture":
                capture(args.role, args.build, args.output)
            case "collect":
                collect(args.plan, args.artifacts, args.output)
            case "coverage":
                coverage(args.report, args.output)
            case "compare":
                compare(args.baseline, args.candidate)
            case _:
                raise BenchmarkError("Invalid benchmark command.")
    except BenchmarkError as exc:
        print(exc, file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, ParseError, DefusedXmlException):
        print("Could not read or write benchmark artifacts.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
