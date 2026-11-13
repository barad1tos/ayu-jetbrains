package dev.ayuislands.studio.persistence

import java.io.IOException

private val transactionFileId = Regex("[A-Za-z0-9_-]{1,128}")

/** The caller validates the payload before writing. Only exact staged and replaced bytes acknowledge it. */
internal fun StudioFiles.writeVerified(
    name: String,
    bytes: ByteArray,
    transactionId: String,
) = whileWritable {
    require(transactionFileId.matches(transactionId)) { "Invalid transaction identifier" }
    val temporary = "$name.tmp.$transactionId"
    writeStaged(temporary, bytes)
    replace(temporary, name)
    requireExact(name, bytes)
}

/** An interrupted create leaves only a staging file; publication cannot replace an existing archive. */
internal fun StudioFiles.createVerified(
    name: String,
    bytes: ByteArray,
) = whileWritable {
    val temporary = "$name.tmp.archive"
    if (read(name) == null) {
        writeStaged(temporary, bytes)
        publishNew(temporary, name)
    }
    requireExact(name, bytes)
    remove(temporary)
}

private fun StudioFiles.writeStaged(
    temporary: String,
    bytes: ByteArray,
) {
    stage(temporary).use { staged ->
        staged.write(bytes)
        staged.force()
    }
    requireExact(temporary, bytes)
}

private fun StudioFiles.requireExact(
    name: String,
    expected: ByteArray,
) {
    if (read(name)?.contentEquals(expected) != true) throw IOException("Store write did not read back exactly")
}
