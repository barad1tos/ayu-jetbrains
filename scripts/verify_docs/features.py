"""features.yml load + flatten-across-categories iterator."""

from __future__ import annotations

from typing import Iterator

import yaml

from .paths import FEATURES_YAML


def load_features() -> dict:
    with FEATURES_YAML.open() as fh:
        return yaml.safe_load(fh)


def iter_features(data: dict) -> Iterator[dict]:
    """Yield every feature dict, flattening across categories.

    Category metadata (id, title) isn't currently consumed by any check —
    if a future lint needs it, expose a companion iterator rather than
    reintroduce an unused tuple field here.
    """
    for cat in data.get("categories", {}).values():
        yield from cat.get("features", [])
