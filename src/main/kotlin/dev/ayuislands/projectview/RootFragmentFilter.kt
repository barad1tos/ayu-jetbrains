package dev.ayuislands.projectview

/** Pure logic for filtering VCS annotation fragments from project root node. */
object RootFragmentFilter {
    fun isKeptFragment(
        trimmed: String,
        projectName: String,
        basePath: String?,
        tildeBasePath: String?,
    ): Boolean {
        if (trimmed.isEmpty()) return false
        if (trimmed == projectName) return true
        if (basePath != null && trimmed.contains(basePath)) return true
        if (tildeBasePath != null && trimmed.contains(tildeBasePath)) return true
        return false
    }
}
