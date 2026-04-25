package dev.ayuislands.accent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pattern I source-regex regression lock for D-09.
 *
 * The `cgpRevertHook` test seam introduced in Wave 1 plan 02 MUST be backed by
 * a `ThreadLocal<...>` — never a `@Volatile var ... -> ((...) -> Unit)?`.
 *
 * Why ThreadLocal? Gradle runs JUnit 5 tests in parallel workers by default. A
 * shared `@Volatile` supplier leaks pinned observers from one test into a
 * concurrent sibling's revertAll() call, producing intermittent
 * "the wrong test's observer fired" false positives. The ThreadLocal isolates
 * the override to the mutating test's own thread. This test pins the seam
 * shape so a future agent who "simplifies" by collapsing the ThreadLocal into
 * a `@Volatile var` re-opens the parallel-worker leak under a named regression.
 *
 * Mirrors the canonical [ChromeDecorationsProbe.osSupplier] template (the
 * Phase 40.4 L-2 fix that introduced this rule for the OS-detection seam).
 */
class AccentApplicatorTestSeamShapeTest {
    private val source: String by lazy {
        val path: Path =
            Paths.get(
                System.getProperty("user.dir"),
                "src",
                "main",
                "kotlin",
                "dev",
                "ayuislands",
                "accent",
                "AccentApplicator.kt",
            )
        stripComments(Files.readString(path))
    }

    private fun stripComments(input: String): String {
        val noBlock = input.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock
            .lineSequence()
            .map { line -> line.replaceFirst(Regex("//.*$"), "") }
            .joinToString("\n")
    }

    private fun extractFunctionBody(
        source: String,
        signaturePrefix: String,
    ): String {
        val start = source.indexOf(signaturePrefix)
        require(start >= 0) { "Could not locate '$signaturePrefix' in stripped source" }
        val openBrace = source.indexOf('{', start)
        require(openBrace >= 0) { "Could not locate opening brace for '$signaturePrefix'" }
        var depth = 1
        var i = openBrace + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth == 0) return source.substring(openBrace + 1, i)
            i++
        }
        error("Unbalanced braces while extracting body for '$signaturePrefix'")
    }

    @Test
    fun `cgpRevertHook is a ThreadLocal not @Volatile var`() {
        // The declaration must use `ThreadLocal<...>` somewhere on its right-hand
        // side. Any of the canonical shapes pass:
        //   `internal val cgpRevertHook: ThreadLocal<...> = ThreadLocal.withInitial { null }`
        //   `private val cgpRevertHook = ThreadLocal.withInitial<...> { null }`
        // The match window deliberately spans newlines because the property
        // declaration may break across lines for readability.
        assertTrue(
            Regex("""cgpRevertHook[\s\S]{0,200}ThreadLocal<""").containsMatchIn(source),
            "cgpRevertHook MUST be declared as ThreadLocal<...> per Pattern I — " +
                "Gradle parallel JUnit workers leak across siblings on a shared " +
                "@Volatile var. See ChromeDecorationsProbe.osSupplier for the " +
                "canonical template.",
        )

        // Forbid the @Volatile var shape explicitly so a "simplification"
        // refactor can't silently downgrade the seam.
        assertFalse(
            Regex("""@Volatile\s+var\s+cgpRevertHook""").containsMatchIn(source),
            "cgpRevertHook MUST NOT be declared as @Volatile var — that shape " +
                "leaks across Gradle parallel workers (Pattern I). Use ThreadLocal.",
        )
    }

    @Test
    fun `resetCgpRevertHookForTests exists and calls remove on the ThreadLocal`() {
        // The teardown helper must clear the per-thread override so a test that
        // forgets the inline try/finally still leaves the seam in production
        // shape for the next sibling test on the same worker.
        val body = extractFunctionBody(source, "fun resetCgpRevertHookForTests(")
        assertTrue(
            Regex("""cgpRevertHook\.remove\(\)""").containsMatchIn(body),
            "resetCgpRevertHookForTests MUST call cgpRevertHook.remove() — the " +
                "ThreadLocal teardown contract (Pattern I). `set(null)` would not " +
                "release the entry on the worker thread; only `.remove()` does.",
        )
    }
}
