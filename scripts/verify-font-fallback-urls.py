#!/usr/bin/env python3
"""Smoke-check FontCatalog fallback font download URLs."""

from __future__ import annotations

import argparse
import http.client
import re
import sys
import time
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
DEFAULT_RETRY_COUNT = 2
DEFAULT_RETRY_DELAY_SECONDS = 0.5
USER_AGENT = "ayu-islands-font-fallback-smoke/1.0"
RETRYABLE_STATUS_CODES = {408, 429, 500, 502, 503, 504}

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
Sleeper = Callable[[float], None]


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
        return (
            self.error is None
            and self.status_code is not None
            and 200 <= self.status_code < 300
        )


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


def check_fallback_url_once(
    fallback_url: FallbackUrl,
    *,
    timeout_seconds: float,
    opener: UrlOpener,
) -> UrlCheckResult:
    request = urllib.request.Request(
        fallback_url.url,
        headers={"User-Agent": USER_AGENT},
        method="HEAD",
    )
    try:
        with opener(request, timeout_seconds) as response:
            status_code = response.getcode()
    except urllib.error.HTTPError as exc:
        return UrlCheckResult(
            constant_name=fallback_url.constant_name,
            url=fallback_url.url,
            status_code=exc.code,
            error=f"HTTP {exc.code}",
        )
    except urllib.error.URLError as exc:
        return UrlCheckResult(
            constant_name=fallback_url.constant_name,
            url=fallback_url.url,
            status_code=None,
            error=f"request failed: {exc.reason}",
        )
    except http.client.HTTPException as exc:
        return UrlCheckResult(
            constant_name=fallback_url.constant_name,
            url=fallback_url.url,
            status_code=None,
            error=f"request failed: {exc}",
        )
    except OSError as exc:
        return UrlCheckResult(
            constant_name=fallback_url.constant_name,
            url=fallback_url.url,
            status_code=None,
            error=f"request failed: {exc}",
        )
    is_success_status = 200 <= status_code < 300
    return UrlCheckResult(
        constant_name=fallback_url.constant_name,
        url=fallback_url.url,
        status_code=status_code,
        error=None if is_success_status else f"HTTP {status_code}",
    )


def should_retry(result: UrlCheckResult) -> bool:
    if result.is_success:
        return False
    return result.status_code is None or result.status_code in RETRYABLE_STATUS_CODES


def check_fallback_urls(
    fallback_urls: Sequence[FallbackUrl],
    *,
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
    opener: UrlOpener = open_head,
    retries: int = DEFAULT_RETRY_COUNT,
    retry_delay_seconds: float = DEFAULT_RETRY_DELAY_SECONDS,
    sleeper: Sleeper = time.sleep,
) -> list[UrlCheckResult]:
    """Run live HEAD checks for each fallback URL."""
    if retries < 0:
        raise ValueError("retries must be greater than or equal to 0.")
    if retry_delay_seconds < 0:
        raise ValueError("retry_delay_seconds must be greater than or equal to 0.")

    results: list[UrlCheckResult] = []
    for fallback_url in fallback_urls:
        result = check_fallback_url_once(
            fallback_url,
            timeout_seconds=timeout_seconds,
            opener=opener,
        )
        for _attempt in range(retries):
            if not should_retry(result):
                break
            sleeper(retry_delay_seconds)
            result = check_fallback_url_once(
                fallback_url,
                timeout_seconds=timeout_seconds,
                opener=opener,
            )
        results.append(result)

    return results


def main(
    argv: Sequence[str] | None = None,
    *,
    opener: UrlOpener = open_head,
    sleeper: Sleeper = time.sleep,
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
    parser.add_argument(
        "--retries",
        type=int,
        default=DEFAULT_RETRY_COUNT,
        help="Number of retries for transient URL smoke failures.",
    )
    parser.add_argument(
        "--retry-delay",
        type=float,
        default=DEFAULT_RETRY_DELAY_SECONDS,
        help="Delay in seconds between URL smoke retries.",
    )
    args = parser.parse_args(argv)

    if args.timeout <= 0:
        print("ERROR: --timeout must be greater than 0.", file=sys.stderr)
        return 2
    if args.retries < 0:
        print("ERROR: --retries must be greater than or equal to 0.", file=sys.stderr)
        return 2
    if args.retry_delay < 0:
        print("ERROR: --retry-delay must be greater than or equal to 0.", file=sys.stderr)
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
        retries=args.retries,
        retry_delay_seconds=args.retry_delay,
        sleeper=sleeper,
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
