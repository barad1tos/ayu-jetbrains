#!/usr/bin/env python3
"""Tests for verify-font-fallback-urls.py."""

from __future__ import annotations

import importlib.util
import http.client
import io
import sys
import tempfile
import textwrap
import unittest
import urllib.error
import urllib.request
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from types import TracebackType
from typing import Callable

SCRIPT = Path(__file__).resolve().parent.parent / "verify-font-fallback-urls.py"

spec = importlib.util.spec_from_file_location("verify_font_fallback_urls", SCRIPT)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT}")
verifier = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = verifier
spec.loader.exec_module(verifier)


class VerifyFontFallbackUrlsTest(unittest.TestCase):
    def test_extracts_only_constants_referenced_as_fallback_url(self) -> None:
        entries = verifier.extract_fallback_urls(
            """
            object FontCatalog {
                private const val USED_URL = "https://example.com/used.zip"
                private const val UNUSED_URL = "https://example.com/unused.zip"
                private const val SECOND_URL =
                    "https://example.com/second.zip"

                val entries = listOf(
                    Entry(fallbackUrl = USED_URL),
                    Entry(fallbackUrl = SECOND_URL),
                    Entry(fallbackUrl = USED_URL),
                )
            }
            """
        )

        self.assertEqual(
            [
                ("USED_URL", "https://example.com/used.zip"),
                ("SECOND_URL", "https://example.com/second.zip"),
            ],
            [(entry.constant_name, entry.url) for entry in entries],
        )

    def test_missing_referenced_constant_is_metadata_error(self) -> None:
        with self.assertRaises(verifier.MetadataError) as context:
            verifier.extract_fallback_urls(
                """
                object FontCatalog {
                    val entries = listOf(Entry(fallbackUrl = MISSING_URL))
                }
                """
            )

        self.assertIn("MISSING_URL", str(context.exception))

    def test_main_returns_2_when_no_fallback_urls_are_found(self) -> None:
        exit_code, _stdout, stderr = run_main(
            """
            object FontCatalog {
                private const val UNUSED_URL = "https://example.com/unused.zip"
                val entries = emptyList<Entry>()
            }
            """,
            opener=successful_opener,
        )

        self.assertEqual(2, exit_code)
        self.assertIn("No fallbackUrl assignments found", stderr)

    def test_main_returns_1_for_non_200_result(self) -> None:
        exit_code, _stdout, stderr = run_main(
            catalog_with_fallback_url("BROKEN_URL", "https://example.com/broken.zip"),
            opener=lambda _request, _timeout: FakeResponse(404),
        )

        self.assertEqual(1, exit_code)
        self.assertIn("BROKEN_URL", stderr)
        self.assertIn("HTTP 404", stderr)

    def test_main_returns_1_for_request_exception(self) -> None:
        def failing_opener(
            _request: urllib.request.Request,
            _timeout: float,
        ) -> FakeResponse:
            raise urllib.error.URLError("timed out")

        exit_code, _stdout, stderr = run_main(
            catalog_with_fallback_url("SLOW_URL", "https://example.com/slow.zip"),
            opener=failing_opener,
        )

        self.assertEqual(1, exit_code)
        self.assertIn("SLOW_URL", stderr)
        self.assertIn("timed out", stderr)

    def test_main_returns_2_for_malformed_http_url_metadata(self) -> None:
        def unexpected_opener(
            _request: urllib.request.Request,
            _timeout: float,
        ) -> FakeResponse:
            self.fail("Malformed metadata must fail before making a request.")

        exit_code, _stdout, stderr = run_main(
            catalog_with_fallback_url("BAD_URL", "https://exa mple.com/font.zip"),
            opener=unexpected_opener,
        )

        self.assertEqual(2, exit_code)
        self.assertIn("BAD_URL", stderr)
        self.assertIn("must not contain whitespace", stderr)

    def test_checker_reports_malformed_request_exception(self) -> None:
        def failing_opener(
            _request: urllib.request.Request,
            _timeout: float,
        ) -> FakeResponse:
            raise http.client.InvalidURL("URL can't contain control characters")

        results = verifier.check_fallback_urls(
            [verifier.FallbackUrl("BAD_URL", "https://exa mple.com/font.zip")],
            opener=failing_opener,
        )

        self.assertEqual(1, len(results))
        self.assertFalse(results[0].is_success)
        self.assertEqual("BAD_URL", results[0].constant_name)
        self.assertIn("URL can't contain control characters", results[0].error)

    def test_checker_retries_transient_exception_then_succeeds(self) -> None:
        attempts = 0
        slept: list[float] = []

        def flaky_opener(
            _request: urllib.request.Request,
            _timeout: float,
        ) -> FakeResponse:
            nonlocal attempts
            attempts += 1
            if attempts == 1:
                raise urllib.error.URLError("temporary timeout")
            return FakeResponse(200)

        results = verifier.check_fallback_urls(
            [verifier.FallbackUrl("FLAKY_URL", "https://example.com/flaky.zip")],
            opener=flaky_opener,
            retries=2,
            retry_delay_seconds=0.25,
            sleeper=slept.append,
        )

        self.assertEqual(2, attempts)
        self.assertEqual([0.25], slept)
        self.assertEqual(1, len(results))
        self.assertTrue(results[0].is_success)

    def test_checker_does_not_retry_non_retryable_http_status(self) -> None:
        attempts = 0

        def missing_opener(
            _request: urllib.request.Request,
            _timeout: float,
        ) -> FakeResponse:
            nonlocal attempts
            attempts += 1
            return FakeResponse(404)

        results = verifier.check_fallback_urls(
            [verifier.FallbackUrl("MISSING_URL", "https://example.com/missing.zip")],
            opener=missing_opener,
            retries=2,
            sleeper=lambda _delay: self.fail("404 must not retry"),
        )

        self.assertEqual(1, attempts)
        self.assertEqual(1, len(results))
        self.assertFalse(results[0].is_success)
        self.assertEqual("HTTP 404", results[0].error)

    def test_main_returns_0_for_http_200_success(self) -> None:
        requests: list[tuple[urllib.request.Request, float]] = []

        def recording_opener(
            request: urllib.request.Request,
            timeout: float,
        ) -> FakeResponse:
            requests.append((request, timeout))
            return FakeResponse(200)

        exit_code, stdout, _stderr = run_main(
            catalog_with_fallback_url("GOOD_URL", "https://example.com/good.zip"),
            opener=recording_opener,
        )

        self.assertEqual(0, exit_code)
        self.assertIn("1 URL(s) checked", stdout)
        self.assertEqual("HEAD", requests[0][0].get_method())
        self.assertEqual(3.0, requests[0][1])


class FakeResponse:
    def __init__(self, status_code: int) -> None:
        self.status_code = status_code

    def __enter__(self) -> FakeResponse:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        return None

    def getcode(self) -> int:
        return self.status_code


def catalog_with_fallback_url(constant_name: str, url: str) -> str:
    return f"""
    object FontCatalog {{
        private const val {constant_name} = "{url}"

        val entries = listOf(Entry(fallbackUrl = {constant_name}))
    }}
    """


def successful_opener(
    _request: urllib.request.Request,
    _timeout: float,
) -> FakeResponse:
    return FakeResponse(200)


def run_main(
    font_catalog: str,
    *,
    opener: Callable[[urllib.request.Request, float], FakeResponse],
) -> tuple[int, str, str]:
    with tempfile.TemporaryDirectory() as temporary_directory:
        catalog_path = Path(temporary_directory) / "FontCatalog.kt"
        catalog_path.write_text(textwrap.dedent(font_catalog), encoding="utf-8")

        stdout = io.StringIO()
        stderr = io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            exit_code = verifier.main(
                ["--font-catalog", str(catalog_path), "--timeout", "3.0"],
                opener=opener,
                sleeper=lambda _delay: None,
            )

    return exit_code, stdout.getvalue(), stderr.getvalue()


if __name__ == "__main__":
    unittest.main()
