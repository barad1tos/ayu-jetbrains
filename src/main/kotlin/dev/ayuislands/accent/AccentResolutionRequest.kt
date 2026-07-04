package dev.ayuislands.accent

/**
 * Bundle of the three seams that distinguish the accent-ladder consumers:
 * where mappings are read from ([AccentMappingsView]), how the language
 * detector is consulted ([AccentDetectorLookup]), and how stored hex values
 * are judged ([AccentHexPolicy]). Ladder ORDER is fixed inside
 * [AccentResolutionChainBuilder]; a request only selects the projection.
 */
internal class AccentResolutionRequest(
    val view: AccentMappingsView,
    val lookup: AccentDetectorLookup,
    val policy: AccentHexPolicy,
) {
    companion object {
        /**
         * Read-only diagnostics walk over persisted state, backing
         * [AccentResolver.resolveChain]: cache-only detector reads and strict
         * hex validation, so the rendered trace never claims a win for a value
         * the applicator would reject.
         */
        fun diagnostics(): AccentResolutionRequest =
            AccentResolutionRequest(
                view = PersistedAccentMappingsView(),
                lookup = AccentDetectorLookup.CacheOnlyLookup,
                policy = AccentHexPolicy.STRICT,
            )

        /**
         * Live walk over persisted state, backing [AccentResolver.resolve] and
         * [AccentResolver.source]: the detector cache may be warmed because the
         * answer is applied to the UI. [policy] stays a parameter because the
         * native and external resolve paths judge invalid hex differently
         * ([AccentHexPolicy.LENIENT] vs [AccentHexPolicy.STRICT]).
         */
        fun liveResolve(policy: AccentHexPolicy): AccentResolutionRequest =
            AccentResolutionRequest(
                view = PersistedAccentMappingsView(),
                lookup = AccentDetectorLookup.WarmingLookup,
                policy = policy,
            )
    }
}
