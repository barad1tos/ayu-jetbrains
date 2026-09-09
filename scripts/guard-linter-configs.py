#!/usr/bin/env -S uv run --script --project scripts
"""Allow only staged Codecov ignore removals; keep other config changes blocked."""

from __future__ import annotations

import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol, cast

import yaml
from yaml.nodes import MappingNode, Node, ScalarNode, SequenceNode

CODECOV = "codecov.yml"
PROTECTED = (CODECOV, "detekt.yml", ".editorconfig")
YAML_PREFIX = "tag:yaml.org,2002:"
SCALAR_TAGS = {YAML_PREFIX + kind for kind in ("str", "bool", "int", "float", "null")}


class YamlComposer(Protocol):
    """Typed boundary for PyYAML's unannotated compose helper."""

    def compose(self, stream: str, loader: type[yaml.SafeLoader], /) -> Node | None: ...


@dataclass(frozen=True)
class ConfigValue:
    tag: str
    value: str | tuple[ConfigValue, ...]


def normalize(node: Node, ancestors: frozenset[int] = frozenset()) -> ConfigValue:
    """Preserve YAML types and reject duplicate keys and recursive aliases."""
    if id(node) in ancestors:
        raise ValueError("Recursive YAML aliases are not supported")
    visited = ancestors | {id(node)}
    if isinstance(node, ScalarNode) and node.tag in SCALAR_TAGS:
        return ConfigValue(node.tag, node.value)
    if isinstance(node, SequenceNode) and node.tag == f"{YAML_PREFIX}seq":
        return ConfigValue(node.tag, tuple(normalize(child, visited) for child in node.value))
    if isinstance(node, MappingNode) and node.tag == f"{YAML_PREFIX}map":
        return normalize_mapping(node, visited)
    raise ValueError(f"Unsupported YAML node: {node.tag}")


def normalize_mapping(node: MappingNode, ancestors: frozenset[int]) -> ConfigValue:
    names: set[str] = set()
    children: list[ConfigValue] = []
    for key, value in node.value:
        if not isinstance(key, ScalarNode) or key.tag != f"{YAML_PREFIX}str":
            raise ValueError("Configuration keys must be strings")
        if key.value in names:
            raise ValueError(f"Duplicate configuration key: {key.value}")
        names.add(key.value)
        children.extend((normalize(key, ancestors), normalize(value, ancestors)))
    return ConfigValue(node.tag, tuple(children))


def read_ignores(node: Node) -> tuple[str, ...]:
    if not isinstance(node, SequenceNode):
        raise TypeError("Codecov ignore must be a list of strings")
    entries: list[str] = []
    for entry in node.value:
        if not isinstance(entry, ScalarNode) or entry.tag != f"{YAML_PREFIX}str":
            raise ValueError("Codecov ignore entries must be strings")
        entries.append(entry.value)
    if len(set(entries)) != len(entries):
        raise ValueError("Duplicate Codecov ignore entries are not supported")
    return tuple(entries)


def read_config(content: str) -> tuple[ConfigValue, tuple[str, ...]]:
    composer = cast(YamlComposer, cast(object, yaml))
    root = composer.compose(content, yaml.SafeLoader)
    if not isinstance(root, MappingNode):
        raise TypeError("Codecov configuration must be a mapping")
    normalize(root)
    remaining: list[ConfigValue] = []
    ignores: tuple[str, ...] = ()
    for key, value in root.value:
        if isinstance(key, ScalarNode) and key.value == "ignore":
            ignores = read_ignores(value)
        else:
            remaining.extend((normalize(key), normalize(value)))
    return ConfigValue(root.tag, tuple(remaining)), ignores


def is_ignore_removal(before: str, after: str) -> bool:
    previous_config, previous_ignores = read_config(before)
    staged_config, staged_ignores = read_config(after)
    return (
        previous_config == staged_config
        and len(staged_ignores) < len(previous_ignores)
        and tuple(item for item in previous_ignores if item in staged_ignores) == staged_ignores
    )


def git(root: Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments],
        cwd=root,
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=True,
        timeout=10,
    ).stdout


def read_snapshots(root: Path) -> tuple[str, str]:
    base = git(root, "ls-tree", "HEAD", "--", CODECOV).split()
    staged = git(root, "ls-files", "--stage", "--", CODECOV).split()
    if len(base) != 4 or len(staged) != 4 or base[0] != staged[0] or base[0] != "100644" or staged[2] != "0":
        raise ValueError("Codecov must remain an existing regular file with unchanged mode and no conflicts")
    return git(root, "show", f"HEAD:{CODECOV}"), git(root, "show", f":{CODECOV}")


def main() -> int:
    try:
        root = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").strip())
        changed = git(root, "diff", "--cached", "--name-only", "--no-renames", "HEAD", "--", *PROTECTED).splitlines()
        if not changed:
            return 0
        if changed != [CODECOV]:
            raise ValueError("Changes to detekt.yml and .editorconfig remain blocked")
        if not is_ignore_removal(*read_snapshots(root)):
            raise ValueError("Only removal of Codecov ignore entries is allowed; preserve all other settings and ignore order")
    except (OSError, subprocess.SubprocessError, TypeError, ValueError, yaml.YAMLError) as error:
        print(f"Linter config modification blocked: {error}", file=sys.stderr)
        return 1
    print("Allowed staged Codecov ignore removal; all other configuration values are unchanged")
    return 0


if __name__ == "__main__":
    sys.exit(main())
