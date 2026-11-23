package spicy.desktop

import com.google.gson.Gson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

class BrowserLink(private val data: File, private val accept: (MediaState) -> Unit) : AutoCloseable {
    companion object { const val PORT = 45687 }
    private data class Report(
        val tab: String?, val title: String?, val artist: String?, val album: String?,
        val duration: Double, val position: Double, val rate: Double,
        val playing: Boolean, val seek: Boolean, val artwork: String?,
    )
    private data class Command(val action: String, val position: Long, val tab: String)
    private data class Cover(val trackKey: String, val encoded: String, val image: BufferedImage)
    private val gson = Gson()
    private val pending = AtomicReference<Command?>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Browser link").apply { isDaemon = true } }
    @Volatile private var server: HttpServer? = null
    @Volatile private var tab = ""
    @Volatile private var isPlaying = false
    @Volatile private var cover: Cover? = null
    @Volatile var lastReport = 0L
        private set

    val token: String by lazy {
        val path = File(data, "browser-link-key")
        val saved = runCatching { path.readText().trim() }.getOrNull()
        if (saved != null && saved.matches(Regex("[a-zA-Z0-9_-]{43}"))) saved
        else Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }).also {
            atomicText(path, it)
        }
    }

    fun start() {
        if (server != null) return
        token
        val listener = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 5)
        listener.executor = worker
        listener.createContext("/browser") { handle(it) }
        listener.start()
        server = listener
    }

    fun control(action: String, position: Long = 0): Boolean {
        val current = tab
        if (current.isBlank() || nowMs() - lastReport > 2500) return false
        pending.set(Command(action, position, current))
        return true
    }

    fun stop() {
        server?.stop(0)
        server = null
        tab = ""
        lastReport = 0
        cover = null
        pending.set(null)
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST" || exchange.requestURI.path != "/browser") {
                exchange.sendResponseHeaders(405, -1); return
            }
            val key = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            if (!MessageDigest.isEqual(key.toByteArray(), ("Bearer " + token).toByteArray())) {
                exchange.sendResponseHeaders(401, -1); return
            }
            val limit = 96 * 1024
            val text = exchange.requestBody.readNBytes(limit + 1)
            val report = if (text.size > limit) null else runCatching { gson.fromJson(text.toString(Charsets.UTF_8), Report::class.java) }.getOrNull()
            if (report == null || report.title.isNullOrBlank() || report.tab.isNullOrBlank()) {
                exchange.sendResponseHeaders(400, -1); return
            }
            val now = nowMs()
            val relevant = tab.isBlank() || report.tab == tab || now - lastReport > 2500 ||
                (!isPlaying && report.playing)
            val output = if (!relevant) "{}" else {
                tab = report.tab.take(90)
                lastReport = now
                isPlaying = report.playing
                val song = Track(report.title.trim().take(400), report.artist.orEmpty().trim().take(400),
                    report.album.orEmpty().trim().take(400), report.duration.coerceIn(0.0, 86_400_000.0).toLong())
                val coverKey = "$tab|${song.key}|${song.album}"
                val previous = cover?.takeIf { it.trackKey == coverKey }
                val encoded = report.artwork
                cover = if (encoded != null && previous?.encoded != encoded) {
                    readCover(encoded)?.let { Cover(coverKey, encoded, it) } ?: previous
                } else previous
                val state = MediaState(song, "browser", report.playing, report.position.coerceAtLeast(0.0).toLong(),
                    now, report.rate.takeIf { it in .05..8.0 }?.toFloat() ?: 1f, true, report.seek, true, art = cover?.image)
                onUi { accept(state) }
                val command = pending.getAndSet(null)?.takeIf { it.tab == tab }
                if (command == null) "{}" else gson.toJson(command)
            }
            val bytes = output.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        } finally {
            exchange.close()
        }
    }

    private fun readCover(encoded: String): BufferedImage? = runCatching {
        if (encoded.length !in 1..(88 * 1024)) return null
        val reader = ImageIO.getImageReadersByFormatName("JPEG").asSequence().firstOrNull() ?: return null
        try {
            val bytes = Base64.getDecoder().decode(encoded)
            MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
                reader.setInput(input, true, true)
                if (reader.getWidth(0) in 1..256 && reader.getHeight(0) in 1..256) reader.read(0) else null
            }
        } finally {
            reader.dispose()
        }
    }.getOrNull()

    override fun close() {
        stop()
        worker.shutdownNow()
    }
}
