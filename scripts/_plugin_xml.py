"""Shared helper: extract dev.ayuislands.* class references from plugin.xml.

Used by verify-bytecode.py and verify-proguard-keeps.py. Kept as a single
module so the "what counts as a plugin-registered class" rule lives in ONE
place — if a future plugin.xml attribute becomes a FQN holder (for example
a new extension-point interface), only this helper needs updating.
"""

from __future__ import annotations

import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PLUGIN_XML = REPO_ROOT / "src" / "main" / "resources" / "META-INF" / "plugin.xml"

# Attribute names IntelliJ platform uses to wire Kotlin/Java classes at
# runtime. Keep this list in sync with any plugin.xml additions that carry
# a FQN — new extension-point slots, listener bindings, etc.
_CLASS_ATTRS = ("implementation", "instance", "serviceImplementation", "class")

_CLASS_ATTR_RE = re.compile(
    r"(?:" + "|".join(_CLASS_ATTRS) + r')="(dev\.ayuislands\.[^"]+)"'
)


def extract_plugin_classes(path: Path = PLUGIN_XML) -> list[str]:
    """Return sorted unique dev.ayuislands.* class FQNs referenced in plugin.xml.

    Only FQNs starting with `dev.ayuislands.` are returned — third-party
    interface references (e.g., `topic="com.intellij..."` listener topics)
    are intentionally filtered out.
    """
    xml = path.read_text(encoding="utf-8")
    return sorted(set(_CLASS_ATTR_RE.findall(xml)))
