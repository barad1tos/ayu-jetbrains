"""Finding + Report — the aggregation surface every check writes into."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class Finding:
    severity: str  # "error" or "warn"
    feature_id: str
    message: str


@dataclass
class Report:
    findings: list[Finding] = field(default_factory=list)

    def error(self, feature_id: str, message: str) -> None:
        self.findings.append(Finding("error", feature_id, message))

    def warn(self, feature_id: str, message: str) -> None:
        self.findings.append(Finding("warn", feature_id, message))

    @property
    def has_errors(self) -> bool:
        return any(f.severity == "error" for f in self.findings)

    def print(self) -> None:
        if not self.findings:
            print("docs/features.yml in sync with README + plugin.xml + CHANGELOG")
            return
        for f in self.findings:
            prefix = "ERROR" if f.severity == "error" else "warn "
            print(f"  [{prefix}] {f.feature_id}: {f.message}")
