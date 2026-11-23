package spicy.desktop

import com.google.gson.Gson
import com.tx24.spicyplayer.lyrics.LocalLyricsStore
import com.tx24.spicyplayer.lyrics.LrcConverter
import com.tx24.spicyplayer.lyrics.LyricsFolderIndex
import com.tx24.spicyplayer.lyrics.LyricsState
import com.tx24.spicyplayer.lyrics.RemoteLyricsAdapter
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.providers.*
import com.tx24.spicyplayer.network.data.spotify.*
import com.tx24.spicyplayer.network.service.HttpLyricsService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LyricsEngine(private val data: File, private val settings: SettingsStore) {
    val client = okhttp3.OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(17, TimeUnit.SECONDS).build()
    val gson = Gson()
    val local = LocalLyricsStore(File(data, "local-lyrics"))
    private val cache = File(data, "lyrics-cache")
    private val cooldown = ProviderCooldownTracker()
    private val nativeNames = NativeNames(client, gson)
    private val genius = GeniusSongPages(client, gson)
    private val synced = ConcurrentHashMap<String, Long>().apply { putAll(settings.value.modelSyncs) }
    private val lrcRed = LrcRedLyricsProvider(client, gson, synced)
    private data class Catalog(val key: String, val custom: List<CustomLyricsSource>, val providers: Set<RemoteLyricsProvider>)
    @Volatile private var catalog: Catalog? = null

    @Synchronized
    private fun catalog(config: Settings): Catalog {
        catalog?.takeIf { it.key == config.clientKey && it.custom == config.custom }?.let { return it }
        val key = config.clientKey.takeIf { it.startsWith("sl_pk_") }
            ?: System.getenv("SPICY_LYRICS_CLIENT_KEY").orEmpty().takeIf { it.startsWith("sl_pk_") }.orEmpty()
        val spotify = SpotifyTrackResolver(AnonymousSpotifyCatalogSearch(client, gson),
            SpicyTrackSearch(client, gson, key), BiniLinkIds(client, gson))
        val builtIn = setOf(
            SpicyLyricsProvider(client, gson, spotify, key),
            AmllLyricsProvider(client, gson),
            UnisonLyricsProvider(client, gson),
            SpicyUnisonLyricsProvider(client, gson, spotify),
            RmmRevivalLyricsProvider(client, gson),
            lrcRed,
            KugouLyricsProvider(client, gson),
            QqMusicLyricsProvider(client, gson),
            KuwoLyricsProvider(client),
            NetEaseLyricsProvider(client, gson),
            LyricsSource(HttpLyricsService(client, gson)),
            LrcMuxLyricsProvider(client, gson),
            GeniusLyricsProvider(genius),
            YouTubeTranscriptLyricsProvider(client, gson),
        )
        return Catalog(config.clientKey, config.custom, builtIn + config.custom.mapIndexed { index, custom ->
            CustomLyricsProvider(custom, client, spotify, 1_000 + index, "Spicy Lyrics for Windows/0.1")
        })
            .also { catalog = it }
    }

    fun descriptors(config: Settings = settings.value) = catalog(config).providers.map { it.descriptor }

    internal fun enabledProviders(config: Settings): List<LyricsSourceDescriptor> {
        val descriptors = descriptors(config)
        val ranking = SourceRanking(descriptors, config.policy)
        return descriptors.filter { ranking.asked(it.id) }.sortedBy { ranking.bestPlace(it.id) }
    }

    suspend fun resolve(track: Track, config: Settings, override: String?, only: String? = null,
                        refresh: Boolean = false,
                        update: suspend (RemoteLyricsResolution) -> Unit): RemoteLyricsResolution {
        val request = track.lookup(config.spotifyIds[track.key])
        val providers = catalog(config).providers
        val ranking = SourceRanking(providers.map { it.descriptor }, config.policy)
        if (only == null || only == "local") {
            val text = override ?: local.get(request.title, request.artist) ?: linkedLyrics(track, config)
            if (text != null) asSelection(text, if (override == null) "Local lyrics" else "Imported lyrics")?.let {
                return RemoteLyricsResolution.Found(it, emptyList())
            }
            if (only == "local") return RemoteLyricsResolution.NotFound(emptyList())
        }
        val policy = if (only == null) config.policy else {
            if (!ranking.asked(only)) return RemoteLyricsResolution.NotFound(emptyList())
            config.policy.copy(pairs = ranking.origins.flatMap { origin ->
                origin.providerIds.map { SourcePair(origin.id, it).key to (it == only && ranking.live(origin, it)) }
            }.toMap())
        }
        val file = File(cache, LocalLyricsStore.keyOf(track.key, gson.toJson(listOf(request, policy, config.custom.map { it.revision }, config.clientKey))) + ".json")
        if (!refresh && only == null && file.lastModified() > System.currentTimeMillis() - 3 * 86_400_000L) {
            runCatching { gson.fromJson(file.readText(), RemoteLyricsResolution.Found::class.java) }.getOrNull()?.let { return it }
        }
        val source = RemoteLyricsSource(providers, cooldown, nativeNames = nativeNames::of)
        val result = source.resolveLyrics(request, policy, onUpdate = update)
        if (only == null && result is RemoteLyricsResolution.Found && result.attempts.none {
            it.outcome in setOf(ProviderAttemptOutcome.UNAVAILABLE, ProviderAttemptOutcome.COOLING_DOWN, ProviderAttemptOutcome.QUEUED)
        }) runCatching { atomicText(file, gson.toJson(result)) }
        return result
    }

    fun clearCache() {
        cache.listFiles()?.filter { it.extension == "json" }?.forEach { it.delete() }
        genius.clear()
    }

    fun asSelection(text: String, label: String): RemoteLyricsSelection? {
        val value = text.trim().removePrefix("\uFEFF")
        val ttml = value.takeIf { it.startsWith("<") } ?: LrcConverter.toTtml(value)
        val payload = if (ttml != null) RemoteLyricsPayload(ttmlLyrics = ttml) else RemoteLyricsPayload(plainLyrics = value)
        val quality = payload.measuredQuality()
        if (quality == RemoteLyricsQuality.NONE) return null
        return RemoteLyricsSelection(LyricsSourceDescriptor("local", label, 0, emptySet()), payload, quality)
    }

    private fun linkedLyrics(track: Track, config: Settings): String? {
        val adjacent = track.file?.let(::File)?.let { file ->
            listOf("ttml", "lrc", "txt").map { File(file.parentFile, file.nameWithoutExtension + "." + it) }.firstOrNull { it.isFile }
        }
        if (adjacent != null) return runCatching { readLyricFile(adjacent) }.getOrNull()
        if (config.lyricsFolder.isBlank()) return null
        return runCatching {
            val folder = File(config.lyricsFolder)
            val index = folder.walkTopDown().maxDepth(16).filter { it.isFile && it.extension.lowercase() in setOf("lrc", "ttml") }
                .take(10_000).mapNotNull { file ->
                    runCatching {
                        LyricsFolderIndex.File(file.absolutePath, file.name, file.lastModified(),
                            LyricsFolderIndex.songsOf(file.name, readLyricFile(file)))
                    }.getOrNull()
                }.toList()
            LyricsFolderIndex.find(index, track.title, track.artist)?.let { readLyricFile(File(it.id)) }
        }.getOrNull()
    }

    suspend fun modelSync(track: Track): ModelSyncResult {
        val config = settings.value
        val ranking = SourceRanking(descriptors(config), config.policy)
        val origin = ranking.origins.firstOrNull { it.id == LyricsOrigins.LRC_RED_AI }
        if (origin == null || !ranking.live(origin, lrcRed.descriptor.id)) return ModelSyncResult.Failed("Enable lrc.red AI syncs in Sources.")
        return lrcRed.requestSync(track.lookup(config.spotifyIds[track.key])).also {
            if (it is ModelSyncResult.Synced) {
                settings.update { value -> value.copy(modelSyncs = synced.toMap()) }
                clearCache()
            }
        }
    }

    suspend fun render(selection: RemoteLyricsSelection, track: Track, config: Settings): LyricsState.Ready {
        val ready = RemoteLyricsAdapter.render(selection, track.duration, config.backgroundVocals, config.romanization, config.writtenJapanese)
        if (!config.romanization || !config.humanRomanization || !RemoteLyricsAdapter.wantsHumanRomanization(ready)) return ready
        val request = track.lookup(config.spotifyIds[track.key])
        val lines = try {
            GeniusRomanizationSource(genius).find(request.title, request.artist, RemoteLyricsAdapter.romanizedTexts(ready))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        }
        return if (lines.isNullOrEmpty()) ready else RemoteLyricsAdapter.withHumanRomanization(ready, lines)
    }

    suspend fun humanTranslation(track: Track, target: String) =
        GeniusTranslationSource(genius).find(track.title, track.artist, target)
}

fun readLyricFile(file: File): String {
    require(file.length() in 1..8_000_000) { "Choose a lyric file smaller than 8 MB." }
    return file.readText()
}
