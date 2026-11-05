package app.pockettavern.data

import app.pockettavern.domain.Sticker
import app.pockettavern.domain.StickerPack
import app.pockettavern.domain.newId
import java.io.File

/** Sticker images on disk under filesDir/stickers/<packId>/; state keeps only the relative paths. */
object StickerStore {
    const val DIR = "stickers"

    private fun root(filesDir: File) = File(filesDir, DIR).canonicalFile

    fun save(filesDir: File, pack: StickerImporter.Pack, source: String): StickerPack {
        val id = newId()
        val folder = File(root(filesDir), id).apply { mkdirs() }
        return try {
            val stickers = pack.items.mapIndexed { index, item ->
                val name = "%03d.%s".format(index + 1, item.extension)
                File(folder, name).writeBytes(item.bytes)
                Sticker(item.key, item.name, item.description, item.tags, "$id/$name")
            }
            StickerPack(id = id, name = pack.name, license = pack.license, source = source.take(120), stickers = stickers)
        } catch (error: Throwable) {
            folder.deleteRecursively()
            throw error
        }
    }

    fun delete(filesDir: File, packId: String) {
        val folder = File(root(filesDir), packId).canonicalFile
        if (packId.isNotBlank() && folder.parentFile == root(filesDir)) folder.deleteRecursively()
    }

    /** The image file for a sticker, or null when the path escapes the folder or the file is gone. */
    fun file(filesDir: File, sticker: Sticker): File? {
        if (!isSafe(sticker.file)) return null
        val file = File(root(filesDir), sticker.file).canonicalFile
        return file.takeIf { it.path.startsWith(root(filesDir).path + File.separator) && it.isFile }
    }

    /**
     * A pack kept on the phone, read back as an upload for the PC. Missing images are left out;
     * a name the PC already uses gets the lowest free number, as a fresh import would.
     */
    fun toUpload(filesDir: File, pack: StickerPack, takenKeys: Set<String>): StickerImporter.Pack {
        val taken = takenKeys.toMutableSet()
        val items = pack.stickers.mapNotNull { sticker ->
            val image = file(filesDir, sticker) ?: return@mapNotNull null
            var key = sticker.key
            var suffix = 2
            while (key in taken) key = "${sticker.key.take(22)}$suffix".also { suffix++ }
            taken += key
            StickerImporter.Item(key, sticker.name, sticker.description, sticker.tags, image.extension.lowercase(), image.readBytes())
        }
        return StickerImporter.Pack(pack.name, pack.license, items)
    }

    /** "<packId>/<name>": two plain segments, nothing that can climb out of the folder. */
    fun isSafe(path: String): Boolean {
        val parts = path.split('/')
        return parts.size == 2 && parts.none { it.isBlank() || it == "." || it == ".." || it.contains('\\') || it.contains(':') }
    }
}
