package dev.ayuislands.glow

internal object GlowIslandIds {
    val all: List<String> = listOf("Editor", "Project", "Terminal", "Run", "Debug", "Git", "Services")

    fun canonical(id: String): String =
        when (id) {
            "Version Control", "Commit" -> "Git"
            else -> id
        }
}
