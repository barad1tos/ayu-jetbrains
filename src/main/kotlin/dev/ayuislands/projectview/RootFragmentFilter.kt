package dev.ayuislands.projectview

/** Context needed to classify root node fragments. */
data class RootNodeContext(
    val projectName: String,
    val basePath: String?,
    val tildeBasePath: String?,
)

/** Pure logic for filtering root node path fragments. */
object RootFragmentFilter {
    fun isPathFragment(
        trimmed: String,
        context: RootNodeContext,
    ): Boolean {
        if (trimmed.isEmpty()) return false
        if (trimmed == context.projectName) return false
        return (context.basePath != null && trimmed.contains(context.basePath)) ||
            (context.tildeBasePath != null && trimmed.contains(context.tildeBasePath))
    }
}
