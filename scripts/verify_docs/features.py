"""features.yml load + flatten-across-categories iterator."""

from __future__ import annotations

from typing import Any, Iterator, cast

import yaml

from .paths import FEATURES_YAML


def load_features() -> dict[str, Any]:
    """Read and parse features.yml as a top-level mapping."""
    with FEATURES_YAML.open() as fh:
        # YAML root in features.yml is always a top-level mapping by schema —
        # the cast tells pyright strict that, since yaml.safe_load is `Any`.
        return cast(dict[str, Any], yaml.safe_load(fh))


def iter_features(data: dict[str, Any]) -> Iterator[dict[str, Any]]:
    """Yield every feature dict, flattening across categories.

    Category metadata (id, title) isn't currently consumed by any check —
    if a future lint needs it, expose a companion iterator rather than
    reintroduce an unused tuple field here.
    """
    for cat in data.get("categories", {}).values():
        yield from cat.get("features", [])
