"""<description> CDATA extractor from plugin.xml."""

from __future__ import annotations

import re

from .paths import PLUGIN_XML


def extract_plugin_xml_description() -> str:
    """Extract the <description> CDATA block from plugin.xml as plain text."""
    xml = PLUGIN_XML.read_text(encoding="utf-8")
    if m := re.search(
        r"<description>\s*<!\[CDATA\[(.*?)]]>\s*</description>",
        xml,
        re.DOTALL,
    ):
        return m[1]
    else:
        raise SystemExit("Could not find <description> CDATA in plugin.xml")
