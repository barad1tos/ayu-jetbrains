"""Finding + Report — the aggregation surface every check writes into."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Finding:
    severity: str  # "error" or "warn"
    feature_id: str
    message: str


def _empty_findings() -> list[Finding]:
    return []


@dataclass
class Report:
    findings: list[Finding] = field(default_factory=_empty_findings)

    def error(self, feature_id: str, message: str) -> None:
        self.findings.append(Finding("error", feature_id, message))

    def warn(self, feature_id: str, message: str) -> None:
        self.findings.append(Finding("warn", feature_id, message))

    @property
    def has_errors(self) -> bool:
        return any(finding.severity == "error" for finding in self.findings)

    def print(self) -> None:
        if not self.findings:
            print("docs/features.yml in sync with README + plugin.xml + CHANGELOG")
            return
        for finding in self.findings:
            prefix = "ERROR" if finding.severity == "error" else "warn "
            print(f"  [{prefix}] {finding.feature_id}: {finding.message}")
