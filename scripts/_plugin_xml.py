"""Shared helper: extract dev.ayuislands.* class references from plugin.xml.

Used by verify-bytecode.py and verify-proguard-keeps.py. Kept as a single
module so the "what counts as a plugin-registered class" rule lives in ONE
place — if a future plugin.xml attribute becomes a FQN holder, the filter
here captures it automatically (we match ANY attribute whose value is
`dev.ayuislands.*`, not a hardcoded whitelist of attribute names).
"""

from __future__ import annotations

import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PLUGIN_XML = REPO_ROOT / "src" / "main" / "resources" / "META-INF" / "plugin.xml"

# Match any `<attr>="dev.ayuislands.Foo"` — the \w+ prefix covers
# implementation / instance / serviceImplementation / class (today) plus
# any future attribute name that carries a FQN. We trust the dev.ayuislands.
# value prefix to filter out third-party references (listener topics from
# com.intellij..., etc.).
_CLASS_REF_RE = re.compile(r'\w+="(dev\.ayuislands\.[^"]+)"')


def extract_plugin_classes(path: Path = PLUGIN_XML) -> list[str]:
    """Return sorted unique dev.ayuislands.* class FQNs referenced in plugin.xml.

    Matches any XML attribute whose quoted value starts with
    `dev.ayuislands.` — implementation, instance, serviceImplementation,
    class, plus any future FQN-carrying attribute IntelliJ adds.
    """
    xml = path.read_text(encoding="utf-8")
    return sorted(set(_CLASS_REF_RE.findall(xml)))
