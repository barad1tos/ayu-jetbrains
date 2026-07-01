#!/usr/bin/env python3
"""Smoke-check FontCatalog fallback font download URLs."""

from __future__ import annotations

import argparse
import http.client
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path
from types import TracebackType
from typing import Protocol

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_FONT_CATALOG = (
    REPO_ROOT
    / "src"
    / "main"
    / "kotlin"
    / "dev"
    / "ayuislands"
    / "font"
    / "FontCatalog.kt"
)
DEFAULT_TIMEOUT_SECONDS = 10.0
USER_AGENT = "ayu-islands-font-fallback-smoke/1.0"

CONSTANT_PATTERN = re.compile(
    r'^\s*(?:(?:private|internal|public)\s+)?const\s+val\s+'
    r'([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"]+)"',
    re.MULTILINE,
)
FALLBACK_ASSIGNMENT_PATTERN = re.compile(r"\bfallbackUrl\s*=\s*([^,\n)]+)")
IDENTIFIER_PATTERN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
WHITESPACE_PATTERN = re.compile(r"\s")


class MetadataError(Exception):
    """Raised when FontCatalog fallback URL metadata cannot be parsed."""


class HeadResponse(Protocol):
    def __enter__(self) -> HeadResponse:
        """Return the open HTTP response."""

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        traceback: TracebackType | None,
    ) -> bool | None:
        """Close the open HTTP response."""

    def getcode(self) -> int:
        """Return the final HTTP status code after redirects."""


UrlOpener = Callable[[urllib.request.Request, float], HeadResponse]


@dataclass(frozen=True)
class FallbackUrl:
    constant_name: str
    url: str


@dataclass(frozen=True)
class UrlCheckResult:
    constant_name: str
    url: str
    status_code: int | None
    error: str | None = None

    @property
    def is_success(self) -> bool:
        return self.status_code == 200 and self.error is None


def extract_fallback_urls(font_catalog_source: str) -> list[FallbackUrl]:
    """Return URL constants referenced by `fallbackUrl = ...` assignments."""
    constants = {
        constant_name: value
        for constant_name, value in CONSTANT_PATTERN.findall(font_catalog_source)
    }
    expressions = [
        expression.strip()
        for expression in FALLBACK_ASSIGNMENT_PATTERN.findall(font_catalog_source)
    ]
    if not expressions:
        raise MetadataError("No fallbackUrl assignments found in FontCatalog.kt.")

    fallback_urls: list[FallbackUrl] = []
    seen_constants: set[str] = set()
    missing_constants: list[str] = []
    for expression in expressions:
        if not IDENTIFIER_PATTERN.fullmatch(expression):
            raise MetadataError(
                "Unsupported fallbackUrl expression in FontCatalog.kt: "
                f"{expression!r}. Expected a constant reference.",
            )
        if expression in seen_constants:
            continue

        seen_constants.add(expression)
        url = constants.get(expression)
        if url is None:
            missing_constants.append(expression)
            continue
        validate_fallback_url_metadata(expression, url)

        fallback_urls.append(FallbackUrl(constant_name=expression, url=url))

    if missing_constants:
        missing = ", ".join(sorted(missing_constants))
        raise MetadataError(f"fallbackUrl references undefined constant(s): {missing}.")

    if not fallback_urls:
        raise MetadataError("No fallback URL constants resolved from FontCatalog.kt.")

    return fallback_urls


def validate_fallback_url_metadata(constant_name: str, url: str) -> None:
    """Validate URL shape before the live HTTP smoke runs."""
    if WHITESPACE_PATTERN.search(url):
        raise MetadataError(
            f"fallbackUrl constant {constant_name} must not contain whitespace, got {url!r}.",
        )

    try:
        parsed_url = urllib.parse.urlsplit(url)
        _port = parsed_url.port
    except ValueError as exc:
        raise MetadataError(
            f"fallbackUrl constant {constant_name} is not a valid URL: {exc}.",
        ) from exc

    if parsed_url.scheme.lower() not in {"http", "https"}:
        raise MetadataError(
            f"fallbackUrl constant {constant_name} must be an HTTP(S) URL, got {url!r}.",
        )
    if not parsed_url.netloc:
        raise MetadataError(
            f"fallbackUrl constant {constant_name} must include a network location, got {url!r}.",
        )


def open_head(request: urllib.request.Request, timeout_seconds: float) -> HeadResponse:
    return urllib.request.urlopen(request, timeout=timeout_seconds)


def check_fallback_urls(
    fallback_urls: Sequence[FallbackUrl],
    *,
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
    opener: UrlOpener = open_head,
) -> list[UrlCheckResult]:
    """Run live HEAD checks for each fallback URL."""
    results: list[UrlCheckResult] = []
    for fallback_url in fallback_urls:
        try:
            request = urllib.request.Request(
                fallback_url.url,
                headers={"User-Agent": USER_AGENT},
                method="HEAD",
            )
            with opener(request, timeout_seconds) as response:
                status_code = response.getcode()
        except urllib.error.HTTPError as exc:
            results.append(
                UrlCheckResult(
                    constant_name=fallback_url.constant_name,
                    url=fallback_url.url,
                    status_code=exc.code,
                    error=f"HTTP {exc.code}",
                )
            )
        except urllib.error.URLError as exc:
            results.append(
                UrlCheckResult(
                    constant_name=fallback_url.constant_name,
                    url=fallback_url.url,
                    status_code=None,
                    error=f"request failed: {exc.reason}",
                )
            )
        except http.client.HTTPException as exc:
            results.append(
                UrlCheckResult(
                    constant_name=fallback_url.constant_name,
                    url=fallback_url.url,
                    status_code=None,
                    error=f"request failed: {exc}",
                )
            )
        except OSError as exc:
            results.append(
                UrlCheckResult(
                    constant_name=fallback_url.constant_name,
                    url=fallback_url.url,
                    status_code=None,
                    error=f"request failed: {exc}",
                )
            )
        else:
            results.append(
                UrlCheckResult(
                    constant_name=fallback_url.constant_name,
                    url=fallback_url.url,
                    status_code=status_code,
                    error=None if status_code == 200 else f"HTTP {status_code}",
                )
            )

    return results


def main(
    argv: Sequence[str] | None = None,
    *,
    opener: UrlOpener = open_head,
) -> int:
    """CLI entry point."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--font-catalog",
        type=Path,
        default=DEFAULT_FONT_CATALOG,
        help="Path to FontCatalog.kt.",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT_SECONDS,
        help="HEAD request timeout in seconds.",
    )
    args = parser.parse_args(argv)

    if args.timeout <= 0:
        print("ERROR: --timeout must be greater than 0.", file=sys.stderr)
        return 2

    try:
        source = args.font_catalog.read_text(encoding="utf-8")
        fallback_urls = extract_fallback_urls(source)
    except OSError as exc:
        print(f"ERROR: unable to read {args.font_catalog}: {exc}", file=sys.stderr)
        return 2
    except MetadataError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    results = check_fallback_urls(
        fallback_urls,
        timeout_seconds=args.timeout,
        opener=opener,
    )
    failures = [result for result in results if not result.is_success]
    if failures:
        print("ERROR: Font fallback URL smoke failed.", file=sys.stderr)
        for failure in failures:
            detail = failure.error or "unknown failure"
            print(
                f"- {failure.constant_name}: {failure.url} -> {detail}",
                file=sys.stderr,
            )
        return 1

    print(f"Font fallback URL smoke passed: {len(results)} URL(s) checked.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
