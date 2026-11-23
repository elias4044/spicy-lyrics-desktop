package spicy.desktop

import java.awt.Font
import java.awt.font.TextAttribute
import java.io.File

internal object LyricFonts {
    enum class Face { Medium, Semibold, Bold }
    private val installed = object : LinkedHashMap<String, Font?>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Font?>) = size > 8
    }

    fun local(path: String): Font? {
        if (path.isBlank()) return null
        return synchronized(installed) {
            if (!installed.containsKey(path)) installed[path] = load(File(path))
            installed[path]
        }
    }

    fun load(file: File): Font? = runCatching {
        require(file.length() in 1..32_000_000)
        if (file.extension.equals("ttc", true)) Font.createFonts(file).firstOrNull()
        else Font.createFont(Font.TRUETYPE_FONT, file)
    }.getOrNull()

    fun face(settings: Settings, face: Face, size: Float): Font {
        val custom = local(if (face == Face.Semibold) settings.backingLyricFont else settings.leadLyricFont)
        val font = custom ?: when (face) {
            Face.Semibold -> Font("Segoe UI Semibold", Font.PLAIN, 16)
            Face.Bold -> Font("Segoe UI", Font.BOLD, 16)
            Face.Medium -> Font("Segoe UI", Font.PLAIN, 16).deriveFont(mapOf(TextAttribute.WEIGHT to TextAttribute.WEIGHT_MEDIUM))
        }
        return font.deriveFont(size)
    }
}
