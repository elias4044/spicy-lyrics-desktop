package spicy.desktop

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.RemoteLyricsPolicy
import com.tx24.spicyplayer.network.data.TrackNameCleaner
import com.tx24.spicyplayer.network.data.providers.CustomLyricsSource
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.SwingUtilities

data class Track(
    val title: String, val artist: String = "", val album: String = "", val duration: Long = 0,
    val file: String? = null,
) {
    val key: String get() = "$title\u001f$artist"
    fun lookup(spotifyId: String? = null): LyricsLookupRequest {
        val clean = TrackNameCleaner.clean(title, artist)
        return LyricsLookupRequest(clean.artist, clean.title, album, ((duration + 500) / 1000).toInt(), spotifyId)
    }
}

data class Settings(
    val policy: RemoteLyricsPolicy = RemoteLyricsPolicy(),
    val custom: List<CustomLyricsSource> = emptyList(),
    val clientKey: String = "",
    val modelSyncs: Map<String, Long> = emptyMap(),
    val spotifyIds: Map<String, String> = emptyMap(),
    val offsets: Map<String, Int> = emptyMap(),
    val globalOffset: Int = 0,
    val lyricsFolder: String = "",
    val background: String = "Motion",
    val style: String = "Spring",
    val rendererDefaultVersion: Int = 1,
    val leadLyricFont: String = "",
    val backingLyricFont: String = "",
    val textScale: Int = 100,
    val lyricGlow: Boolean = true,
    val distanceBlur: Boolean = true,
    val plainAutoScroll: Boolean = true,
    val waitBubbles: Boolean = true,
    val showUpNext: Boolean = true,
    val fullscreenStage: Boolean = true,
    val backgroundVocals: Boolean = true,
    val romanization: Boolean = false,
    val writtenJapanese: Boolean = true,
    val humanRomanization: Boolean = false,
    val translation: Boolean = false,
    val translationLanguage: String = "en",
    val translator: String = "Google",
    val translationKey: String = "",
    val translationConsent: Boolean = false,
    val humanTranslation: Boolean = false,
    val translationEndpoint: String = "",
    val translationModel: String = "",
    val coverStage: Boolean = false,
    val coverRight: Boolean = false,
    val reduceMotion: Boolean = false,
    val alwaysOnTop: Boolean = false,
    val hidePrevious: Boolean = false,
    val fullCover: Boolean = false,
    val credits: Boolean = true,
    val welcomeSeen: Boolean = false,
)

class SettingsStore(private val file: File) {
    private val gson = Gson()
    @Volatile var value = read(file) ?: Settings()
        private set

    @Synchronized
    fun update(change: (Settings) -> Settings) {
        val next = change(value)
        atomicText(file, gson.toJson(next))
        value = next
    }

    fun export(file: File) {
        val settings = value
        atomicText(file, gson.toJson(settings.copy(clientKey = "", translationKey = "", translationConsent = false, humanRomanization = false,
            custom = settings.custom.map { it.copy(headers = emptyList()) })))
    }

    fun restore(file: File) {
        val next = read(file) ?: error("This isn't a desktop settings backup.")
        update { current -> next.copy(clientKey = current.clientKey, welcomeSeen = current.welcomeSeen,
            translationKey = current.translationKey.takeIf { current.translator == next.translator &&
                current.translationEndpoint == next.translationEndpoint }.orEmpty(),
            translationConsent = false, humanRomanization = false,
            custom = next.custom.map { custom -> custom.copy(headers = current.custom.find { it.id == custom.id }?.headers.orEmpty()) }) }
    }

    private fun read(file: File): Settings? = runCatching {
        val defaults = gson.toJsonTree(Settings()).asJsonObject
        val saved = gson.fromJson(file.readText(), JsonObject::class.java)
        require(saved.keySet().any(defaults::has))
        saved.entrySet().filter { defaults.has(it.key) && !it.value.isJsonNull }.forEach { defaults.add(it.key, it.value) }
        if (!saved.has("welcomeSeen")) defaults.addProperty("welcomeSeen", true)
        // Older builds stored Bloom even when the user had never picked a renderer.
        if (!saved.has("rendererDefaultVersion")) defaults.addProperty("style", "Spring")
        gson.fromJson(defaults, Settings::class.java)
    }.getOrNull()
}

fun atomicText(file: File, text: String) {
    val path = file.absoluteFile.toPath()
    Files.createDirectories(path.parent)
    val temp = Files.createTempFile(path.parent, "spicy-", ".tmp")
    try {
        Files.writeString(temp, text)
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temp)
    }
}

fun onUi(block: () -> Unit) = SwingUtilities.invokeLater(block)
fun nowMs() = System.nanoTime() / 1_000_000
