package spicy.desktop

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.playback.LyricClock
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

data class MediaSession(val id: String, val playing: Boolean) {
    val name: String get() = when {
        id.contains("spotify", true) -> "Spotify"
        id.contains("msedge", true) || id.contains("MicrosoftEdge", true) -> "Microsoft Edge"
        id.contains("chrome", true) -> "Google Chrome"
        id.contains("firefox", true) -> "Firefox"
        id.contains("vlc", true) -> "VLC"
        id.contains("zunemusic", true) -> "Windows Media Player"
        id.contains("applemusic", true) -> "Apple Music"
        else -> id.substringBefore('!').substringAfterLast('\\').removeSuffix(".exe")
    }
}

data class MediaState(
    val track: Track? = null, val session: String = "", val playing: Boolean = false,
    val position: Long = 0, val at: Long = nowMs(), val rate: Float = 1f,
    val clock: Boolean = false, val seek: Boolean = false, val toggle: Boolean = false,
    val next: Boolean = false, val previous: Boolean = false, val art: BufferedImage? = null,
    val error: String? = null, val upcoming: UpcomingTrack? = null,
)

data class UpcomingTrack(val title: String, val artist: String = "", val art: BufferedImage? = null)

class Playback(private val helper: File, private val changed: (MediaState) -> Unit) : AutoCloseable {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "Windows media").apply { isDaemon = true } }
    private val gson = Gson()
    private var process: Process? = null
    private var reader: java.io.BufferedReader? = null
    private var writer: java.io.BufferedWriter? = null
    private val clock = LyricClock(nowMs())
    private var localFile: String? = null
    private var localDuration = 0L
    private var last: MediaState = MediaState()
    private var fetchedCover: String? = null
    private var coverAttempts = 0
    private var nextCoverPoll = 0L
    private var lastSeek = 0L
    private var tail: List<File> = emptyList()
    private var queueIndex = 0
    private var currentMode = ""
    private var describedFile: File? = null
    private var describedTrack: UpcomingTrack? = null
    @Volatile var session: String = ""
        private set
    @Volatile private var closed = false

    init {
        worker.scheduleWithFixedDelay({ if (session !in setOf("manual", "demo", "browser")) poll() }, 0, 235, TimeUnit.MILLISECONDS)
    }

    private fun request(vararg pairs: Pair<String, Any>): JsonObject {
        if (closed) error("Playback has closed.")
        if (process?.isAlive != true) {
            require(helper.isFile) { "Windows media bridge is missing." }
            process = ProcessBuilder(helper.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            writer = process!!.outputStream.bufferedWriter(Charsets.UTF_8)
            reader = process!!.inputStream.bufferedReader(Charsets.UTF_8)
        }
        val command = gson.toJson(pairs.toMap())
        writer!!.apply { write(command); newLine(); flush() }
        val output = reader!!.readLine() ?: error("Windows media bridge closed.")
        val value = gson.fromJson(output, JsonObject::class.java)
        value.text("error").takeIf(String::isNotBlank)?.let { error(it) }
        return value
    }

    fun sessions(done: (List<MediaSession>, String?) -> Unit) {
        worker.execute {
            val result = runCatching {
                request("cmd" to "list").getAsJsonArray("sessions")?.map {
                    it.asJsonObject.let { item -> MediaSession(item.text("id"), item.flag("playing")) }
                }.orEmpty().distinctBy { it.id }
            }
            onUi { done(result.getOrDefault(emptyList()), result.exceptionOrNull()?.message) }
        }
    }

    fun use(mode: String) {
        worker.execute {
            if (session == "local" && mode != session) runCatching { request("cmd" to "control", "session" to "local", "action" to "pause") }
            session = mode
            last = MediaState()
            fetchedCover = null
            lastSeek = 0
            if (mode !in setOf("manual", "demo", "browser")) poll()
        }
    }

    fun open(files: List<File>, done: (String?) -> Unit) {
        worker.execute {
            val result = runCatching { openFile(files.first()) }
            onUi { done(result.exceptionOrNull()?.message) }
            if (result.isSuccess) {
                tail = files
                queueIndex = 0
                describedFile = null
                describedTrack = null
                poll()
            }
        }
    }

    private fun openFile(file: File) {
        val value = request("cmd" to "open", "session" to "local", "path" to file.absolutePath)
        localFile = file.absolutePath
        localDuration = value.number("duration")
        session = "local"
        last = MediaState()
        fetchedCover = null
        lastSeek = 0
    }

    fun control(action: String, position: Long = 0, done: (Boolean) -> Unit) {
        worker.execute {
            val accepted = runCatching {
                if (session == "local" && action in setOf("next", "previous")) {
                    val target = queueIndex + if (action == "next") 1 else -1
                    if (target !in tail.indices) false else {
                        openFile(tail[target])
                        queueIndex = target
                        true
                    }
                } else request("cmd" to "control",
                    "session" to last.session.ifBlank { session }, "action" to action, "position" to position).flag("accepted")
            }.getOrDefault(false)
            if (accepted) {
                if (action == "seek") {
                    lastSeek = nowMs()
                    clock.seekTo(position, lastSeek)
                    last = last.copy(position = position, at = lastSeek)
                }
                poll()
            }
            onUi { done(accepted) }
        }
    }

    private fun poll() {
        val selected = session
        val result = runCatching { request("cmd" to "poll", "session" to selected) }.getOrElse {
            val error = it.message ?: "Can't read Windows media sessions."
            if (last.error != error) publish(MediaState(error = error))
            return
        }
        val title = result.text("title")
        if (title.isBlank()) {
            fetchedCover = null
            if (last.track != null || last.session != selected || last.error != null) publish(MediaState(session = selected))
            return
        }
        val now = nowMs()
        val id = result.text("id")
        val track = Track(title, result.text("artist"), result.text("album"),
            result.number("duration").takeIf { it > 0 } ?: if (selected == "local") localDuration else 0,
            localFile.takeIf { selected == "local" })
        val identity = "$id|"+track.key+"|"+track.file.orEmpty()
        val fresh = last.track?.key != track.key || id != last.session || currentMode != selected || last.track?.file != track.file
        currentMode = selected
        val playing = result.flag("playing")
        val speed = result.decimal("rate").takeIf { it in .05..8.0 }?.toFloat() ?: 1f
        val clockAvailable = result.flag("clock")
        val age = result.number("age")
        val updated = if (selected == "local" || age < 10L) now else now - age.coerceAtMost(86_400_000)
        if (fresh) lastSeek = 0
        if (fresh || now - lastSeek > 650) {
            val state = if (playing) 3 else 2
            val raw = result.number("position")
            val report = LyricClock.Report(state, playing && clockAvailable, raw, updated, speed)
            if (fresh || selected == "local" || clock.isFresh(report)) clock.onReport(report, now, snap = fresh || selected == "local")
        }
        val position = clock.positionAt(now).let { if (track.duration > 0) it.coerceAtMost(track.duration) else it }
        if (selected == "local" && !playing && track.duration > 0 && position >= track.duration - 180 && queueIndex + 1 < tail.size) {
            val nextIndex = queueIndex + 1
            if (runCatching { openFile(tail[nextIndex]) }.isSuccess) {
                queueIndex = nextIndex
                return
            }
        }
        var state = MediaState(track, id, playing, position, now, speed, clockAvailable, result.flag("seek"),
            result.flag("toggle"), if (selected == "local") queueIndex + 1 < tail.size else result.flag("next"),
            if (selected == "local") queueIndex > 0 else result.flag("previous"), if (!fresh) last.art else null)
        val nextFile = if (selected == "local") tail.getOrNull(queueIndex + 1) else null
        if (nextFile != null && nextUpDue(position, track.duration)) {
            if (describedFile != nextFile) {
                describedFile = nextFile
                describedTrack = runCatching {
                    val details = request("cmd" to "describe", "path" to nextFile.absolutePath)
                    val pixels = details.text("cover").takeIf(String::isNotBlank)?.let {
                        runCatching { ImageIO.read(Base64.getDecoder().decode(it).inputStream()) }.getOrNull()
                    }
                    UpcomingTrack(details.text("title").ifBlank { nextFile.nameWithoutExtension }, details.text("artist"), pixels)
                }.getOrElse { UpcomingTrack(nextFile.nameWithoutExtension) }
            }
            state = state.copy(upcoming = describedTrack)
        }
        publish(state)
        if (fetchedCover != identity) {
            fetchedCover = identity
            coverAttempts = 0
            nextCoverPoll = 0L
        }
        if (state.art == null && coverAttempts < 4 && now >= nextCoverPoll) {
            nextCoverPoll = now + (1_000L shl coverAttempts++)
            val artwork = runCatching {
                val reply = request("cmd" to "poll", "session" to id, "cover" to true)
                val cover = reply.text("cover").takeIf {
                    reply.text("title") == track.title && reply.text("artist") == track.artist
                }.orEmpty()
                if (cover.isBlank()) null else ImageIO.read(Base64.getDecoder().decode(cover).inputStream())
            }.getOrNull()
            if (artwork != null) publish(state.copy(art = artwork))
        }
    }

    private fun publish(state: MediaState) {
        if (state == last) return
        last = state
        onUi { if (!closed) changed(state) }
    }

    override fun close() {
        closed = true
        process?.destroy()
        worker.shutdownNow()
    }
}

private fun JsonObject.text(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
private fun JsonObject.number(key: String) = get(key)?.asLong ?: 0L
private fun JsonObject.decimal(key: String) = get(key)?.asDouble ?: 0.0
private fun JsonObject.flag(key: String) = get(key)?.asBoolean ?: false
