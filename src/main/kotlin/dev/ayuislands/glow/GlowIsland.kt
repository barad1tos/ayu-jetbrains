package dev.ayuislands.glow

/** The platform window IDs that share each Glow toggle. */
internal enum class GlowIsland(
    val id: String,
    vararg aliases: String,
) {
    EDITOR("Editor"),
    PROJECT("Project"),
    TERMINAL("Terminal"),
    RUN("Run"),
    DEBUG("Debug"),
    GIT("Git", "Version Control", "Commit"),
    SERVICES("Services"),
    ;

    private val nativeIds = listOf(id) + aliases

    companion object {
        private val islands = entries.flatMap { island -> island.nativeIds.map { it to island } }.toMap()

        fun fromId(id: String): GlowIsland? = islands[id]
    }
}
