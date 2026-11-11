package app.pockettavern.data

import java.io.File

/**
 * A character's avatar is either a picture shipped with the app ("asset:characters/x.png") or one
 * the user chose or imported, stored under filesDir/avatars ("file:avatars/x.png").
 */
object CharacterAvatars {
    const val DIR = "avatars"
    private val assetPattern = Regex("asset:characters/[a-z0-9_-]{1,64}\\.png")
    private val filePattern = Regex("file:avatars/[A-Za-z0-9._-]{1,120}\\.png")

    fun normalize(value: String?): String = value.orEmpty().trim().takeIf { assetPattern.matches(it) || filePattern.matches(it) }.orEmpty()

    fun assetPath(avatar: String): String? = avatar.takeIf { assetPattern.matches(it) }?.removePrefix("asset:")

    /** The stored picture of a user avatar, kept inside [filesDir]/avatars; null for anything else. */
    fun file(filesDir: File, avatar: String): File? {
        if (!filePattern.matches(avatar)) return null
        val root = File(filesDir, DIR).canonicalFile
        val file = File(filesDir, avatar.removePrefix("file:")).canonicalFile
        return file.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    /** A file name for a new avatar of [characterId]; the timestamp makes every change a new picture. */
    fun newFileName(characterId: String, now: Long): String =
        "${characterId.filter { it.isLetterOrDigit() || it in "._-" }.take(60).ifBlank { "character" }}-$now.png"
}
