package spicy.desktop

import com.tx24.spicyplayer.lyrics.LyricsState
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Cursor
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.math.*

class Stage(
    private val position: () -> Long,
    seek: (Long) -> Unit,
    private val onCover: () -> Unit,
) : JComponent() {
    var track: Track? = null
        set(value) {
            if (field == value) return
            if (field?.title != value?.title) trackShownAt = nowMs()
            field = value
            home?.isVisible = value == null && !mini
            wake()
        }
    var playing = false
        set(value) { if (field != value) { field = value; wake() } }
    var lyrics: LyricsState.Ready? = null
    var settings = Settings()
        set(value) {
            val changed = field.fullCover != value.fullCover || field.coverStage != value.coverStage ||
                field.coverRight != value.coverRight
            field = value
            if (changed) reveal.enter()
        }
    var presentation = false
        set(value) {
            if (field == value) return
            field = value
            reveal.enter()
        }
    internal var presentationControls: JComponent? = null
    internal var upcoming: UpcomingTrack? = null
        set(value) {
            if (field == value) return
            field = value
            if (value != null) visibleNext = value
            nextChangedAt = nowMs()
            wake()
        }
    private var visibleNext: UpcomingTrack? = null
    private var nextChangedAt = 0L
    private var notification: String? = null
    private var notificationAt = 0L
    var mini = false
        set(value) {
            field = value
            home?.isVisible = track == null && !value
            reveal.enter()
            wake()
        }
    var artwork: BufferedImage? = null
        set(value) {
            if (field === value) return
            field = value
            scene?.setCover(value)
            wake()
        }
    var status = "Play something to get started"
    var detail = "Start music in another app or choose a playback source."
    var loading = false
    internal var scene: ArtworkScene? = null
    internal var repaintScene: () -> Unit = ::repaint
    private var home: JComponent? = null
    private val reveal = RevealMotion(this)
    private val surface = LyricSurface(seek)
    private val returnToLine = PlayerButton("Back to current line", "sync", { scene?.theme ?: CoverTheme.Neutral },
        prominent = true, size = 32) { resetScroll() }.apply { isVisible = false }
    private var coverHit = Rectangle()
    private var lastEvent = 0L
    private var lastRepaint = 0L
    private var trackShownAt = 0L
    private var titleLayer: BufferedImage? = null
    private val timer = Timer(16) {
        val now = nowMs()
        val animateLyrics = playing && (mini || !settings.fullCover) ||
            loading && !settings.reduceMotion || now - lastEvent < 5_000
        val animateBackground = artwork != null && !settings.reduceMotion && settings.background == "Motion"
        val minimumInterval = if (animateLyrics && !settings.reduceMotion) 0 else 29
        if (isShowing && (animateLyrics || animateBackground) && now - lastRepaint >= minimumInterval) {
            lastRepaint = now
            repaintScene()
        }
    }

    init {
        isOpaque = false
        isFocusable = true
        layout = null
        add(returnToLine)
        toolTipText = ""
        addMouseWheelListener { event ->
            if ((!settings.fullCover || mini) && !lyrics?.lines.isNullOrEmpty() &&
                surface.wheel(event.point, event.preciseWheelRotation, nowMs())) wake()
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button != MouseEvent.BUTTON1) return
                if (coverHit.contains(event.point)) onCover()
                else if (track != null && !lyrics?.lines.isNullOrEmpty() &&
                    (!settings.fullCover || mini) && surface.seekAt(event.point)) wake()
            }
            override fun mouseExited(event: MouseEvent) {
                cursor = Cursor.getDefaultCursor()
                surface.hover(null)
                wake()
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                val canSeek = track != null && !lyrics?.lines.isNullOrEmpty() &&
                    (!settings.fullCover || mini) && surface.canSeekAt(event.point)
                surface.hover(event.point.takeUnless { coverHit.contains(it) || settings.fullCover && !mini })
                wake()
                cursor = if (coverHit.contains(event.point) || canSeek) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    else Cursor.getDefaultCursor()
            }
        })
    }

    internal fun setHome(content: JComponent) {
        home = content
        content.isVisible = track == null && !mini
        add(content)
    }

    override fun paint(graphics: Graphics) = reveal.paint(graphics) { super.paint(it) }

    override fun doLayout() {
        home?.let {
            val size = it.preferredSize
            val w = min(size.width, width)
            val h = min(size.height, height)
            it.setBounds((width - w) / 2, (height - h) / 2, w, h)
        }
        val controls = presentationControls ?: return
        if (!presentation) { controls.setBounds(0, 0, 0, 0); return }
        val cover = focusLayout().cover
        controls.setBounds(cover.x, cover.y, cover.width, cover.height + 36)
    }

    private data class FocusLayout(val cover: Rectangle, val lyrics: Rectangle, val stacked: Boolean)

    private fun focusLayout(): FocusLayout {
        if (width < 820) {
            val size = min(width * .64, (height - 105) * .42).toInt().coerceAtLeast(1)
            val y = 15
            val lyricsY = y + size + 124
            return FocusLayout(Rectangle((width - size) / 2, y, size, size),
                Rectangle(14, lyricsY, width - 28, (height - lyricsY).coerceAtLeast(0)), true)
        }
        val w = (width * .462).toInt()
        val size = min((w * .74).toInt(), (height * .66).toInt()).coerceIn(1, 700)
        val coverX = (if (settings.coverRight) width - w else 0) + (w - size) / 2
        val y = ((height - size - 135) * .46).toInt().coerceAtLeast(14)
        val lyricsX = if (settings.coverRight) 26 else w + 6
        return FocusLayout(Rectangle(coverX, y, size, size),
            Rectangle(lyricsX, 0, width - w - 42, height), false)
    }

    override fun getToolTipText(event: MouseEvent): String? = if (coverHit.contains(event.point)) {
        if (settings.fullCover) "Show lyrics" else "Show cover"
    } else null

    override fun addNotify() { super.addNotify(); timer.start() }
    override fun removeNotify() { timer.stop(); super.removeNotify() }
    fun wake() { lastEvent = nowMs(); repaintScene() }
    fun resetScroll() { surface.reset(); returnToLine.isVisible = false; wake() }
    internal fun notify(message: String) {
        notification = message
        notificationAt = nowMs()
        wake()
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.quality()
        val now = nowMs()
        coverHit = Rectangle()
        val song = track
        if (song == null || settings.fullCover && !mini) returnToLine.isVisible = false
        if (song == null) {
            if (home?.isVisible != true) paintEmpty(g, Rectangle(16, 0, width - 32, height), status, detail, now)
        } else if (presentation) {
            val layout = focusLayout()
            val art = layout.cover
            paintCover(g, art.x, art.y, art.width)
            paintTrack(g, song, art.x, art.y + art.height + 70, art.width, centered = true)
            paintLyrics(g, layout.lyrics, now)
            if (layout.stacked || art.y + art.height + 164 > height) paintNext(g, width - 288, height - 67, 267, now)
            else paintNext(g, art.x + (art.width - min(art.width, 310)) / 2,
                art.y + art.height + 110, min(art.width, 310), now)
        } else if (mini) {
            paintLyrics(g, Rectangle(19, 0, width - 38, height), now)
            paintNext(g, width - 230, height - 53, 213, now, compact = true)
        } else if (settings.fullCover) {
            val size = min((height * .72).toInt(), (width * .72).toInt()).coerceAtLeast(25)
            val y = ((height - size - 69) * .46).toInt().coerceAtLeast(8)
            paintCover(g, (width - size) / 2, y, size)
            paintTrack(g, song, 20, y + size + 39, width - 40, centered = true)
            paintNext(g, width - 288, height - 63, 266, now)
        } else if (width >= 800) {
            val playerWidth = (width * .405).toInt()
            val playerX = if (settings.coverRight) width - playerWidth else 0
            val lyricsX = if (settings.coverRight) 17 else playerWidth + 11
            val lyricsWidth = width - playerWidth - 29
            val size = min((playerWidth * .72).toInt(), (height * .66).toInt()).coerceIn(48, 390)
            val coverX = playerX + (playerWidth - size) / 2
            val coverY = ((height - size - 62) * .51).toInt().coerceAtLeast(8)
            if (settings.coverStage && artwork != null) {
                paintCoverStage(g, playerX, playerWidth, height)
                paintTrack(g, song, coverX, height - 51, size, centered = false)
            } else {
                paintCover(g, coverX, coverY, size)
                paintTrack(g, song, coverX, coverY + size + 40, size, centered = false)
            }
            paintLyrics(g, Rectangle(lyricsX, 0, lyricsWidth, height), now)
            val nextY = coverY + size + 77
            if (nextY + 54 <= height) paintNext(g, coverX, nextY, min(size, 302), now)
            else paintNext(g, width - 288, height - 60, 267, now)
        } else {
            val size = min((width * .21).toInt(), (height * .24).toInt()).coerceIn(44, 108)
            val x = 26
            paintCover(g, x, 10, size)
            val available = width - x - size - 37
            val textX = x + size + 14
            val baseline = 10 + size / 2 + 1
            g.font = font(if (size > 67) 21 else 17, true)
            paintTitle(g, song.title, textX, baseline, available, false)
            g.font = font(if (size > 67) 15 else 13)
            g.color = scene?.theme?.muted ?: MUTED
            g.drawString(fit(g, song.artist, available), textX, baseline + 23)
            paintLyrics(g, Rectangle(12, size + 18, width - 24, height - size - 18), now)
            paintNext(g, width - 277, height - 63, 255, now)
        }
        paintNotification(g, now)
        g.dispose()
    }

    private fun paintNotification(g: Graphics2D, now: Long) {
        val message = notification ?: return
        val age = now - notificationAt
        if (age >= 3_500) { notification = null; return }
        val opacity = if (settings.reduceMotion) 1.0 else
            smoothstep(age / 150.0) * smoothstep((3_500 - age) / 220.0)
        if (width < 100 || height < 40) return
        (g.create() as Graphics2D).useGraphics { pill ->
            pill.composite = AlphaComposite.SrcOver.derive(opacity.toFloat())
            pill.font = font(13, true)
            val text = fit(pill, message, (width - 48).coerceAtMost(580))
            val w = pill.fontMetrics.stringWidth(text) + 28
            val x = (width - w) / 2
            val y = height - 44 + ((1 - opacity) * 8).toInt()
            val theme = scene?.theme ?: CoverTheme.Neutral
            pill.color = sheetColor(theme).alpha(.97)
            val shell = rounded(x, y, w, 33, 22)
            pill.fill(shell)
            pill.color = theme.accent.alpha(.22)
            pill.draw(shell)
            pill.color = TEXT
            pill.drawString(text, x + 14, y + 21)
        }
    }

    private fun paintLyrics(g: Graphics2D, bounds: Rectangle, now: Long) {
        if (lyrics?.lines.isNullOrEmpty()) {
            returnToLine.isVisible = false
            paintEmpty(g, bounds, status, detail, now)
            return
        }
        surface.update(lyrics, settings)
        surface.paint(g, bounds, position(), now, mini, track?.duration ?: 0, presentation)
        val button = returnToLine.preferredSize
        val showReturn = surface.browsingAway && bounds.width >= button.width + 16 && bounds.height >= 51
        if (showReturn) returnToLine.setBounds(bounds.x + bounds.width - button.width - 8, bounds.y + 8, button.width, button.height)
        returnToLine.isVisible = showReturn
    }

    private fun paintNext(g: Graphics2D, x: Int, y: Int, width: Int, now: Long, compact: Boolean = false) {
        val info = visibleNext ?: return
        if (width < 120 || y < 0) return
        val p = if (settings.reduceMotion || !isShowing) 1.0 else smoothstep((now - nextChangedAt) / 290.0)
        val opacity = if (upcoming != null) p else 1.0 - p
        if (opacity <= .001) return
        val h = if (compact) 43 else 54
        val top = y + ((1 - opacity) * 10).toInt()
        (g.create() as Graphics2D).useGraphics { card ->
            card.composite = AlphaComposite.SrcOver.derive(opacity.toFloat())
            val theme = scene?.theme ?: CoverTheme.Neutral
            val pill = rounded(x, top, width, h, 20)
            card.color = mix(theme.base, Color.WHITE, .10).alpha(.94)
            card.fill(pill)
            card.color = theme.accent.alpha(.20)
            card.draw(pill)
            val cover = h - 14
            if (info.art != null) {
                (card.create() as Graphics2D).useGraphics {
                    it.clip(rounded(x + 7, top + 7, cover, cover, 10))
                    it.cropped(info.art, x + 7, top + 7, cover, cover)
                }
            } else {
                card.color = theme.accent.alpha(.13)
                card.fill(rounded(x + 7, top + 7, cover, cover, 10))
                glyph(card, "next", x + 7 + cover / 2.0, top + h / 2.0, 16.0, theme.accent)
            }
            val textX = x + cover + 17
            val available = x + width - textX - 11
            card.font = font(11, true)
            card.color = theme.accent
            card.drawString("Up next", textX, top + if (compact) 15 else 16)
            card.font = font(13, true)
            card.color = TEXT
            card.drawString(fit(card, info.title, available), textX, top + 32)
            if (!compact && info.artist.isNotBlank()) {
                card.font = font(11)
                card.color = MUTED
                card.drawString(fit(card, info.artist, available), textX, top + 46)
            }
        }
    }

    private fun paintTrack(g: Graphics2D, song: Track, x: Int, y: Int, max: Int, centered: Boolean) {
        if (max < 10) return
        fun draw(text: String, baseline: Int) {
            val value = fit(g, text, max)
            g.drawString(value, if (centered) x + (max - g.fontMetrics.stringWidth(value)) / 2 else x, baseline)
        }
        g.font = font(if (width > 1000) 29 else 24, true)
        paintTitle(g, song.title, x, y, max, centered)
        g.font = font(15)
        g.color = scene?.theme?.muted ?: MUTED
        draw(song.artist.ifBlank { song.album }, y + 24)
    }

    private fun paintTitle(g: Graphics2D, title: String, x: Int, baseline: Int, available: Int, centered: Boolean) {
        if (available < 10) return
        val metrics = g.fontMetrics
        val textWidth = metrics.stringWidth(title)
        val scrolling = !settings.reduceMotion && (playing || artwork != null && settings.background == "Motion")
        g.color = TEXT
        if (textWidth <= available || !scrolling) {
            val value = fit(g, title, available)
            g.drawString(value, if (centered) x + (available - metrics.stringWidth(value)) / 2 else x, baseline)
            return
        }
        val scale = g.transform.scaleX.coerceIn(1.0, 2.5)
        val pixels = ceil(available * scale).toInt()
        val height = metrics.height + 4
        val pixelHeight = ceil(height * scale).toInt()
        val layer = titleLayer?.takeIf { it.width == pixels && it.height == pixelHeight }
            ?: BufferedImage(pixels, pixelHeight, BufferedImage.TYPE_INT_ARGB_PRE).also { titleLayer = it }
        val circuit = textWidth + 38
        val at = ((nowMs() - trackShownAt - 1_350).coerceAtLeast(0) * .034) % circuit
        layer.createGraphics().useGraphics { text ->
            text.composite = AlphaComposite.Clear
            text.fillRect(0, 0, pixels, pixelHeight)
            text.composite = AlphaComposite.SrcOver
            text.quality()
            text.scale(scale, scale)
            text.font = g.font
            text.color = TEXT
            val line = metrics.ascent + 2
            text.drawString(title, -at.toFloat(), line.toFloat())
            text.drawString(title, (circuit - at).toFloat(), line.toFloat())
            text.composite = AlphaComposite.DstIn
            val edge = min(17, available / 3)
            val transparent = Color.WHITE.alpha(0.0)
            text.paint = GradientPaint((available - edge).toFloat(), 0f, Color.WHITE, available.toFloat(), 0f, transparent)
            text.fillRect(available - edge, 0, edge, height)
            if (at > .5) {
                text.paint = GradientPaint(0f, 0f, transparent, edge.toFloat(), 0f, Color.WHITE)
                text.fillRect(0, 0, edge, height)
            }
        }
        g.drawImage(layer, x, baseline - metrics.ascent - 2, available, height, null)
    }

    private fun paintCover(g: Graphics2D, x: Int, y: Int, size: Int) {
        coverHit = Rectangle(x, y, size, size)
        val radius = (size * .085).coerceIn(11.0, 24.0)
        for (i in 23 downTo 3 step 4) {
            g.color = Color.BLACK.alpha(.011 + (23 - i) * .0009)
            g.fill(rounded(x - i / 2, y + 4 - i / 2, size + i, size + i, radius + i))
        }
        val shape = rounded(x, y, size, size, radius)
        val cover = g.create() as Graphics2D
        cover.clip(shape)
        val image = artwork
        if (image != null) cover.cropped(image, x, y, size, size)
        else {
            cover.paint = GradientPaint(x.toFloat(), y.toFloat(), Color(28, 28, 28), (x + size).toFloat(), (y + size).toFloat(), Color.BLACK)
            cover.fillRect(x, y, size, size)
            glyph(cover, "note", x + size / 2.0, y + size / 2.0, size * .27, Color.WHITE.alpha(.75))
        }
        cover.dispose()
        g.color = Color.WHITE.alpha(.17)
        g.draw(shape)
    }

    private fun paintCoverStage(g: Graphics2D, x: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val image = artwork ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val start = if (settings.coverRight) w else 0f
        val end = if (settings.coverRight) 0f else w
        val sceneColor = scene?.theme?.base ?: Color.BLACK
        val layer = g.create(x, 0, width, height) as Graphics2D
        layer.cropped(image, 0, 0, width, height)
        layer.paint = GradientPaint(start, 0f, sceneColor.alpha(.08), end, 0f, sceneColor)
        layer.fillRect(0, 0, width, height)
        layer.paint = GradientPaint(0f, h * .42f, Color.BLACK.alpha(0.0), 0f, h, sceneColor.alpha(.97))
        layer.fillRect(0, 0, width, height)
        layer.dispose()
        coverHit = Rectangle(x, 0, width, height)
    }

    private fun paintEmpty(g: Graphics2D, bounds: Rectangle, title: String, body: String, now: Long) {
        if (bounds.width < 20 || bounds.height < 25) return
        val x = bounds.x + bounds.width / 2
        val y = bounds.y + bounds.height / 2
        if (loading) {
            val pulse = if (settings.reduceMotion) .20 else .17 + .07 * sin(now / 285.0)
            for (i in 0..2) {
                val length = min((bounds.width * listOf(.55, .39, .49)[i]).toInt(), 310)
                g.color = Color.WHITE.alpha(pulse * (1 - i * .18))
                g.fill(rounded(x - length / 2, y - 57 + i * 20, length, 8, 8))
            }
        }
        val offset = if (loading) 22 else 0
        g.font = font(21, true)
        g.color = TEXT
        val headline = fit(g, title, bounds.width - 18)
        g.drawString(headline, x - g.fontMetrics.stringWidth(headline) / 2, y + offset)
        if (!mini || bounds.height > 130) {
            g.font = font(13)
            g.color = scene?.theme?.muted ?: MUTED
            val description = fit(g, body, bounds.width - 18)
            g.drawString(description, x - g.fontMetrics.stringWidth(description) / 2, y + 24 + offset)
        }
    }

    companion object {
        fun fit(g: Graphics2D, text: String, width: Int): String {
            if (width <= 0) return ""
            if (g.fontMetrics.stringWidth(text) <= width) return text
            var end = text.length
            while (end > 0 && g.fontMetrics.stringWidth(text.substring(0, end) + "…") > width) {
                end = text.offsetByCodePoints(end, -1)
            }
            return text.substring(0, end).trimEnd() + "…"
        }
    }
}
