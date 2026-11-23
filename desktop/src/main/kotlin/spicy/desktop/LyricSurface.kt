package spicy.desktop

import com.tx24.spicyplayer.lyrics.LyricsState
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.Point
import java.awt.Rectangle
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import kotlin.math.*

internal class LyricSurface(private val seek: (Long) -> Unit) {
    private var document: LyricsState.Ready? = null
    private var config = Settings()
    private var layout: LyricLayout? = null
    private var restingScroll = Float.NaN
    private var fromScroll = 0f
    private var toScroll = 0f
    private var scrollAt = 0L
    private var scrollDuration = 500
    private var scrollIndex = -1
    private var manualTarget: Float? = null
    private var manualUntil = 0L
    var browsingAway = false
        private set
    private var lastPosition = Double.NaN
    private var lastPaint = 0L
    private var hits = emptyList<Pair<Rectangle, Long>>()
    private var pointer: Point? = null
    private val rowHover = mutableMapOf<Int, Double>()
    private var lastBounds = Rectangle()
    private var maximumScroll = 0f
    private var rowTops = FloatArray(0)
    private var viewport: BufferedImage? = null
    private val halo = LyricHalo()
    private val spring = SpringSimulation(0f, 1f, 1f)
    private val blurCache = object : LinkedHashMap<Pair<LyricBox, Int>, BufferedImage>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<LyricBox, Int>, BufferedImage>) = size > 27
    }

    fun update(lyrics: LyricsState.Ready?, settings: Settings) {
        if (document !== lyrics || config.textScale != settings.textScale || config.style != settings.style ||
            config.romanization != settings.romanization || config.translation != settings.translation ||
            config.waitBubbles != settings.waitBubbles || config.leadLyricFont != settings.leadLyricFont ||
            config.backingLyricFont != settings.backingLyricFont) {
            layout = null
            blurCache.clear()
            rowHover.clear()
        }
        config = settings
        document = lyrics
    }

    fun reset() {
        restingScroll = Float.NaN
        lastPosition = Double.NaN
        manualTarget = null
        manualUntil = 0L
        browsingAway = false
        hits = emptyList()
        pointer = null
        rowHover.clear()
    }

    fun wheel(point: Point, amount: Double, now: Long): Boolean {
        if (layout == null) return false
        if (!lastBounds.contains(point)) return false
        val from = manualTarget ?: restingScroll.takeUnless { it.isNaN() } ?: 0f
        manualTarget = (from + amount.toFloat() * 67f).coerceIn(0f, maximumScroll)
        manualUntil = now + 3_800
        return true
    }

    fun seekAt(point: Point): Boolean {
        val target = hits.firstOrNull { it.first.contains(point) }?.second ?: return false
        manualTarget = null
        manualUntil = 0L
        browsingAway = false
        seek(target)
        return true
    }

    fun canSeekAt(point: Point) = hits.any { it.first.contains(point) }

    fun hover(point: Point?) { pointer = point }

    fun paint(g: Graphics2D, bounds: Rectangle, time: Long, now: Long, mini: Boolean, duration: Long = 0, presentation: Boolean = false) {
        hits = emptyList()
        lastBounds = bounds
        val doc = document ?: return
        if (bounds.width < 30 || bounds.height < 24 || doc.lines.isEmpty()) return
        val frc = g.fontRenderContext
        val measured = layout?.takeIf { it.width == bounds.width && it.frc == frc && it.mini == mini }
            ?: layoutLyrics(frc, doc, bounds.width, mini, config).also {
                layout = it
                rowTops = FloatArray(it.lines.size)
                blurCache.clear()
                restingScroll = Float.NaN
            }
        if (measured.lines.isEmpty()) return
        val timed = doc.lyricsType != LyricsType.Static
        val bloom = config.style == "Bloom"
        val t = time - if (bloom) BloomMotion.POSITION_SHIFT_MS else 0.0
        var collapsed = 0f
        for ((index, box) in measured.lines.withIndex()) {
            rowTops[index] = box.top - collapsed
            if (box.isInterlude) {
                val enter = smoothstep((t - box.line.startMs + 80) / 270)
                val leave = smoothstep((box.line.endMs - t) / 480)
                val presence = if (config.reduceMotion) {
                    if (t >= box.line.startMs && t < box.line.endMs) 1.0 else 0.0
                } else if (bloom) enter * leave
                else SpringLyricsMotion.dotGroup(t - box.line.startMs, box.line.endMs - box.line.startMs).toDouble().coerceAtMost(1.0)
                collapsed += box.height * (1 - presence.toFloat())
            }
        }
        maximumScroll = if (timed) rowTops.last().coerceAtLeast(0f) else
            (rowTops.last() + measured.lines.last().inkHeight - bounds.height * .83f).coerceAtLeast(0f)
        val autoPlain = !timed && config.plainAutoScroll && duration > 0L
        val rawAnchor = if (timed) measured.activeAt(t) else -1
        val activeIndex = measured.anchors.getOrElse(rawAnchor) { -1 }
        val nextIndex = if (timed) measured.anchors.getOrElse(rawAnchor + 1) { -1 } else -1
        val active = measured.lines.getOrNull(activeIndex)
        val next = measured.lines.getOrNull(nextIndex)
        val groupEnd = if (active != null) measured.lines.asSequence().drop(activeIndex + 1)
            .takeWhile { it.isBackground && it.line.groupId == active.line.groupId }
            .fold(active.sungEnd) { end, background -> max(end, background.sungEnd) } else 0L
        val scroll = BloomMotion.scrollFor(next?.let { it.line.startMs - groupEnd } ?: 0L)
        val scrollAnchor = if (bloom && !mini && next != null && active != null &&
            t >= next.line.startMs - min(110, scroll.leadMs)) nextIndex else activeIndex
        val dt = ((now - lastPaint) / 1000f).coerceIn(0f, .05f)
        val jumped = !lastPosition.isNaN() && abs(t - lastPosition) > 300 + (now - lastPaint)
        if (jumped) manualTarget = null
        val followTarget = if (autoPlain) {
            (maximumScroll * plainScrollProgress(time, duration)).toFloat()
        } else rowTops.getOrElse(scrollAnchor) { 0f }
        val browsed = manualTarget?.coerceIn(0f, maximumScroll)
        browsingAway = timed && browsed != null && abs(browsed - followTarget) >= (bounds.height * .46f)
        if ((timed || autoPlain) && !browsingAway && now >= manualUntil) manualTarget = null
        val target = manualTarget?.coerceIn(0f, maximumScroll) ?: followTarget
        if (restingScroll.isNaN() || jumped || config.reduceMotion || abs(target - restingScroll) > bounds.height * 1.8) {
            fromScroll = target
            toScroll = target
            restingScroll = target
            scrollIndex = scrollAnchor
            scrollAt = now - 1000
            spring.setGoal(target, replacePosition = true)
        } else if (!timed) {
            val amount = 1f - exp(-dt / .13f)
            restingScroll += (target - restingScroll) * amount
            fromScroll = restingScroll
            toScroll = target
        } else if (target != toScroll || scrollIndex != scrollAnchor) {
            val reflow = scrollIndex == scrollAnchor && manualTarget == null &&
                abs(target - toScroll) < 20f && measured.interludes.isNotEmpty()
            if (reflow) {
                fromScroll += target - toScroll
                restingScroll += target - toScroll
                toScroll = target
                spring.setGoal(target)
            } else {
                fromScroll = restingScroll
                toScroll = target
                scrollAt = now
                scrollIndex = scrollAnchor
                val anchor = measured.anchors.indexOf(scrollAnchor)
                val previous = measured.anchors.getOrNull(anchor - 1)?.let(measured.lines::get)
                val current = measured.lines.getOrNull(scrollAnchor)
                scrollDuration = if (manualTarget != null) 230 else BloomMotion.scrollFor(
                    if (current != null && previous != null) current.line.startMs - previous.sungEnd else 750L
                ).ms
                spring.setGoal(target)
            }
        }
        fun scrollFor(index: Int): Float {
            if (!timed || !bloom || config.reduceMotion) return restingScroll
            val delay = if (manualTarget != null) 0 else BloomMotion.rowDelayMs(index - scrollIndex)
            val p = BloomMotion.SCROLL_EASE.transform((now - scrollAt - delay) / scrollDuration.toFloat())
            return fromScroll + (toScroll - fromScroll) * p
        }
        if (timed) restingScroll = if (bloom) scrollFor(scrollIndex) else spring.step(dt)
        lastPaint = now
        lastPosition = t

        val focusY = bounds.height * when {
            !timed -> .12f
            mini -> .28f
            presentation -> .43f
            else -> .31f
        }
        val scaleX = g.transform.scaleX.coerceIn(1.0, 2.5)
        val scaleY = g.transform.scaleY.coerceIn(1.0, 2.5)
        val pixelWidth = ceil(bounds.width * scaleX).toInt()
        val pixelHeight = ceil(bounds.height * scaleY).toInt()
        val image = viewport?.takeIf { it.width == pixelWidth && it.height == pixelHeight }
            ?: BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB_PRE).also { viewport = it }
        val canvas = image.createGraphics()
        canvas.composite = AlphaComposite.Clear
        canvas.fillRect(0, 0, pixelWidth, pixelHeight)
        canvas.composite = AlphaComposite.SrcOver
        canvas.quality()
        canvas.scale(pixelWidth / bounds.width.toDouble(), pixelHeight / bounds.height.toDouble())
        val haloCanvas = if (timed && config.lyricGlow) halo.begin(bounds.width, bounds.height) else null
        val haloProjection = haloCanvas?.let { AffineTransform(it.transform).apply { concatenate(canvas.transform.createInverse()) } }
        var paintedGlow = false
        val clickable = mutableListOf<Pair<Rectangle, Long>>()
        val low = min(restingScroll, fromScroll) - focusY - 200
        val high = max(restingScroll, toScroll) + bounds.height - focusY + 80
        val startIndex = rowTops.binarySearch(low).let { if (it < 0) (-it - 2).coerceAtLeast(0) else it }
        for (index in startIndex until measured.lines.size) {
            val box = measured.lines[index]
            val top = rowTops[index]
            if (top > high) break
            val y = top - scrollFor(index) + focusY
            if (y + box.height < -16 || y > bounds.height + 12) continue
            val line = box.line
            val before = t < line.startMs
            val after = t > max(line.startMs + 1, box.sungEnd)
            val singing = timed && !line.untimed && !before && !after
            val manual = manualTarget != null
            if (box.isInterlude && (!timed || before || t >= line.endMs)) continue
            val visibility = if (timed && config.hidePrevious && !manual && !mini && after && index < activeIndex) {
                1.0 - smoothstep((t - box.sungEnd) / 340)
            } else 1.0
            if (visibility <= .01) continue
            val distance = if (activeIndex < 0) index else abs(index - activeIndex)
            val whole = doc.lyricsType == LyricsType.Line || line.lineTimed || line.untimed
            val sinceStart = t - line.startMs
            val sinceEnd = t - box.sungEnd
            val springWords = timed && !bloom && !whole && !box.isInterlude && box.spans.isNotEmpty()
            val wordFocus = if (springWords) smoothstep((sinceStart + 190) / 190) else 0.0
            val dim = if (!timed) .86 else if (bloom) {
                when {
                    box.isBackground -> .18
                    singing -> .32
                    after -> .16 + .16 * (1.0 - smoothstep(sinceEnd / 430))
                    else -> .25
                }
            } else when {
                springWords && !after -> (if (box.isBackground) .30 else .50) * (.51 + .49 * wordFocus)
                springWords -> {
                    val idle = (if (box.isBackground) .60 else .85) * .497
                    val active = if (box.isBackground) .30 else .50
                    idle + (active - idle) * (1 - smoothstep(sinceEnd / 350))
                }
                singing && box.isBackground -> .30
                singing && whole -> .35
                singing -> .50
                after && box.isBackground -> .60 * .497
                after -> .85 * .497
                box.isBackground -> .30 * .51
                whole -> .35 * .51
                else -> .50 * .51
            }
            val bright = if (box.isBackground) if (bloom) .55 else .60 else if (bloom) .97 else .85
            val introEase = if (bloom) BloomMotion.LINE_TONE_EASE else BloomMotion.SCALE_EASE
            val introDuration = if (bloom) 300 else 190
            val intro = introEase.transform((sinceStart / introDuration).toFloat())
            val tone = if (!timed || before) 0.0 else if (!after) intro.toDouble() else {
                val delay = if (bloom && !whole) BloomMotion.DIM_DELAY_MS else 0
                val duration = if (whole) BloomMotion.LINE_DIM_MS else BloomMotion.DIM_MS
                val endTone = introEase.transform((box.sungEnd - line.startMs).toFloat() / introDuration)
                endTone * (1.0 - BloomMotion.DIM_EASE.transform(((sinceEnd - delay) / duration).toFloat()))
            }
            val scale = if (!timed || config.reduceMotion || box.isInterlude || !whole) 1.0 else if (bloom) {
                val emphasis = if (!after) BloomMotion.SCALE_EASE.transform(
                    ((sinceStart + BloomMotion.SCALE_IN_DELAY_MS) / BloomMotion.SCALE_IN_MS).toFloat()
                ) else BloomMotion.SCALE_EASE.transform(
                    ((box.sungEnd - line.startMs + BloomMotion.SCALE_IN_DELAY_MS) / BloomMotion.SCALE_IN_MS).toFloat()
                ) * (1f - BloomMotion.SCALE_EASE.transform(
                    ((sinceEnd - BloomMotion.SCALE_OUT_DELAY_MS) / BloomMotion.SCALE_OUT_MS).toFloat()
                ))
                (BloomMotion.INACTIVE_SCALE + (1 - BloomMotion.INACTIVE_SCALE) * emphasis).toDouble()
            } else 1.0 + .05 * tone
            val layer = canvas.create() as Graphics2D
            layer.translate(0.0, y.toDouble())
            if (scale != 1.0) {
                val pivotX = if (box.right) bounds.width.toDouble() else 0.0
                layer.translate(pivotX, box.size.toDouble())
                layer.scale(scale, scale)
                layer.translate(-pivotX, -box.size.toDouble())
            }
            val live = !whole && (singing || after && sinceEnd < 950) && box.spans.isNotEmpty()
            val hover = if (timed && !line.untimed && !box.isInterlude) {
                val margin = (bounds.width * .015).toInt()
                val top = max(bounds.y, bounds.y + y.roundToInt() - 8)
                val bottom = min(bounds.y + bounds.height, bounds.y + (y + box.inkHeight + 8).roundToInt())
                val hit = Rectangle(bounds.x + margin, top, bounds.width - margin * 2, bottom - top)
                if (hit.height > 0) clickable += hit to line.startMs
                val hovered = pointer?.let(hit::contains) == true
                val previous = rowHover[index] ?: 0.0
                val amount = if (config.reduceMotion) {
                    if (hovered) 1.0 else 0.0
                } else previous + ((if (hovered) 1.0 else 0.0) - previous) * (1 - exp(-dt / .055))
                if (!hovered && amount < .008) rowHover.remove(index) else rowHover[index] = amount
                if (amount > .008) {
                    canvas.color = Color.WHITE.alpha(.135 * amount * visibility)
                    canvas.fill(rounded(margin.toDouble(), y - 8.0, (bounds.width - margin * 2).toDouble(), box.inkHeight + 16.0, 17.0))
                }
                amount
            } else 0.0
            val flames = if (live && bloom && !config.reduceMotion) HashMap<HeldWord, BloomMotion.Flame>() else null
            val light = if (haloCanvas != null && haloProjection != null && (live || tone > .05 || box.isInterlude))
                HaloLight(haloCanvas, haloProjection)
            else null
            when {
                box.isInterlude -> paintDots(layer, box, sinceStart, visibility, bounds.width, bloom, light)
                whole && timed && !line.untimed && (singing || after && tone > .015) -> {
                    val progress = (sinceStart / (box.sungEnd - line.startMs).coerceAtLeast(1)).coerceIn(0.0, 1.0)
                    val amount = if (after) tone else 1.0
                    paintLineWipe(layer, box, progress, dim * visibility, (dim + (bright - dim) * amount) * visibility, light)
                }
                live -> {
                    val fade = box.size * BloomMotion.FADE_EM
                    val edges = if (bloom) BloomMotion.sweepEdges(box.spans, box.starts, box.ends, fade, t) else null
                    var p = 0
                    val amount = if (after) tone else 1.0
                    for (row in box.rows) {
                        for (piece in row.pieces) {
                            val fill = edges?.get(p)
                            paintWord(layer, row, piece, box, t, fill, amount, dim * visibility,
                                (dim + (bright - dim) * amount) * visibility, flames, light)
                            p++
                        }
                    }
                }
                else -> {
                    val alpha = dim + (bright - dim) * tone
                    val blur = if (!timed || !config.distanceBlur || manual || singing || distance == 0) 0
                        else if (bloom) (distance * 1.9 + 1.5).roundToInt().coerceIn(2, 12)
                        else (distance * 1.25 * box.size / 48).roundToInt().coerceIn(1, 7)
                    // Crossfade focus without dropping the soft mask the moment a line changes.
                    val focus = if (springWords && before) wordFocus else tone
                    val sharp = if (blur == 0) 1.0 else max(focus, hover)
                    if (sharp > .01) paintRows(layer, box, bounds.width, alpha * visibility * sharp, 0)
                    if (sharp < .99) paintRows(layer, box, bounds.width, alpha * visibility * (1 - sharp), blur)
                    if (light != null && tone > .02 && !line.untimed) {
                        for (row in box.rows) {
                            val text = row.layout.getOutline(AffineTransform.getTranslateInstance(row.x.toDouble(), row.baseline.toDouble()))
                            light.paint(layer, text, Color.WHITE.alpha(.59 * tone * visibility))
                        }
                    }
                }
            }
            for (row in box.underRows.takeUnless { whole && timed && !line.untimed && (singing || after && tone > .015) }.orEmpty()) {
                if (row.pieces.isNotEmpty() && live) {
                    for (piece in row.pieces) paintWord(layer, row, piece, box, t, null, tone,
                        dim * visibility * .83, (dim + (.72 - dim) * if (after) tone else 1.0) * visibility,
                        flames, secondary = true)
                } else {
                    layer.color = Color.WHITE.alpha((dim * .83 + (.67 - dim * .83) * tone) * visibility)
                    row.layout.draw(layer, row.x, row.baseline)
                }
            }
            paintedGlow = paintedGlow || light?.painted == true
            layer.dispose()
        }
        if (haloCanvas != null) {
            haloCanvas.dispose()
            if (paintedGlow) halo.composite(canvas, bounds.width, bounds.height)
        }
        fadeEdges(canvas, bounds.width, bounds.height)
        canvas.dispose()
        g.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, null)
        hits = clickable
    }

    private fun paintLineWipe(g: Graphics2D, box: LyricBox, progress: Double, dim: Double, bright: Double, light: HaloLight?) {
        val feather = (box.inkHeight * .20f).coerceAtLeast(1f)
        val edge = ((box.inkHeight + feather) * progress).toFloat()
        g.paint = GradientPaint(0f, edge - feather, Color.WHITE.alpha(bright), 0f, edge, Color.WHITE.alpha(dim))
        val glow = light?.let { GradientPaint(0f, edge - feather, Color.WHITE.alpha(bright * .57), 0f, edge, Color.WHITE.alpha(0.0)) }
        for (row in box.rows + box.underRows) {
            row.layout.draw(g, row.x, row.baseline)
            if (light != null && glow != null && edge > 1f) {
                val shape = row.layout.getOutline(AffineTransform.getTranslateInstance(row.x.toDouble(), row.baseline.toDouble()))
                light.paint(g, shape, glow)
            }
        }
    }

    private fun fadeEdges(g: Graphics2D, width: Int, height: Int) {
        val edge = min(70f, height * .28f)
        g.composite = AlphaComposite.DstIn
        g.paint = LinearGradientPaint(0f, 0f, 0f, edge, maskStops, maskColors)
        g.fillRect(0, 0, width, ceil(edge).toInt())
        g.paint = LinearGradientPaint(0f, height.toFloat(), 0f, height - edge, maskStops, maskColors)
        g.fillRect(0, floor(height - edge).toInt(), width, ceil(edge).toInt())
    }

    private fun paintRows(g: Graphics2D, box: LyricBox, width: Int, alpha: Double, blur: Int) {
        if (alpha < .012) return
        if (blur > 0 && box.inkHeight < 460 && box.rows.isNotEmpty()) {
            val image = blurCache.getOrPut(box to blur) {
                val source = BufferedImage(width, ceil(box.inkHeight + 32).toInt(), BufferedImage.TYPE_INT_ARGB_PRE)
                source.createGraphics().apply {
                    quality()
                    color = Color.WHITE
                    for (row in box.rows) row.layout.draw(this, row.x, row.baseline + 16)
                    dispose()
                }
                blurImage(source, blur)
            }
            g.composite = AlphaComposite.SrcOver.derive(alpha.toFloat())
            g.drawImage(image, 0, -16, null)
            g.composite = AlphaComposite.SrcOver
        } else {
            g.color = Color.WHITE.alpha(alpha)
            for (row in box.rows) {
                row.layout.draw(g, row.x, row.baseline)
            }
        }
    }

    private fun paintWord(
        g: Graphics2D, row: LyricRow, piece: LyricPiece, box: LyricBox, t: Double, edge: Double?,
        amount: Double, dim: Double, bright: Double, flames: MutableMap<HeldWord, BloomMotion.Flame>?,
        light: HaloLight? = null,
        secondary: Boolean = false,
    ) {
        if (config.style != "Bloom") {
            paintSpringWord(g, row, piece, box, t, amount, dim, bright, light, secondary)
            return
        }
        val word = piece.word
        val duration = (word.endMs - word.startMs).coerceAtLeast(1)
        val elapsed = t - word.startMs
        val progress = (elapsed / duration).coerceIn(0.0, 1.0)
        val size = if (secondary) box.size * .49f else box.size
        val px = size * BloomMotion.FADE_EM
        val glyphs = piece.glyphs
        val x = glyphs.left
        val wipe = if (edge == null) {
            when {
                progress >= 1.0 -> (glyphs.width + px).toDouble()
                progress <= 0.0 -> -px.toDouble()
                else -> -px * .5 + (glyphs.width + px) * progress
            }
        } else edge - piece.span.x
        val shift = if (glyphs.rtl) x + glyphs.width - wipe else x + wipe
        val liftElapsed = t - piece.liftStart
        val motion = !config.reduceMotion && liftElapsed > 0
        val held = piece.heldWord
        val transform = g.transform
        g.translate(row.x.toDouble(), row.baseline.toDouble())
        fun sweep(low: Double, high: Double): java.awt.Paint = when {
            wipe <= -px * .5 -> Color.WHITE.alpha(low)
            wipe >= glyphs.width + px * .5 -> Color.WHITE.alpha(high)
            else -> GradientPaint((shift - px * .5).toFloat(), 0f, Color.WHITE.alpha(if (glyphs.rtl) low else high),
                (shift + px * .5).toFloat(), 0f, Color.WHITE.alpha(if (glyphs.rtl) high else low))
        }
        val fill = sweep(dim, bright)
        g.paint = fill
        if (motion && held != null && flames != null) {
            val flame = flames.getOrPut(held) { BloomMotion.flame(t - held.start, held.duration, held.letterCount) }
            val base = g.transform
            for ((index, letter) in piece.letters.withIndex()) {
                val i = index + piece.letterOffset
                g.translate(0.0, flame.y(i) * size * amount)
                if (light != null && wipe > -px * .5) {
                    light.paint(g, letter.shape, sweep(0.0, bright * (.61 + flame.glow(i) * .7)))
                }
                g.fill(letter.shape)
                g.transform = base
            }
        } else {
            if (motion) {
                val lift = -BloomMotion.LIFT_EM * BloomMotion.lift(liftElapsed)
                g.translate(0.0, lift * size * amount)
            }
            if (light != null && wipe > -px * .5) light.paint(g, glyphs.shape, sweep(0.0, bright * .59))
            g.fill(glyphs.shape)
        }
        g.transform = transform
    }

    private fun paintSpringWord(
        g: Graphics2D, row: LyricRow, piece: LyricPiece, box: LyricBox, t: Double,
        effect: Double, dim: Double, bright: Double, light: HaloLight?, secondary: Boolean,
    ) {
        val word = piece.word
        val duration = (word.endMs - word.startMs).coerceAtLeast(1)
        val elapsed = t - word.startMs
        val parent = g.transform
        val size = if (secondary) box.size * .49 else box.size.toDouble() / if (box.isBackground) .75 else 1.0
        val reduced = config.reduceMotion
        val pose = SpringLyricsMotion.pose(SpringLyricsMotion.Kind.Word, elapsed, duration, reduced)
        g.translate(row.x.toDouble(), row.baseline.toDouble())
        g.translate(0.0, pose.lift * size * effect * smoothstep(elapsed / 95))
        fun emit(glyph: GlyphSlice, progress: Double, glow: Double) {
            g.paint = springFill(glyph, progress, dim, bright)
            g.fill(glyph.shape)
            if (light != null && glow > .022) {
                light.paint(g, glyph.shape, springFill(glyph, progress, 0.0, glow.coerceAtMost(.9)))
            }
        }
        if (piece.letters.isEmpty()) {
            emit(piece.glyphs, elapsed / duration, pose.glow * effect * if (box.isBackground) .41 else .79)
        } else {
            val wordTransform = g.transform
            for ((index, letter) in piece.letters.withIndex()) {
                val start = duration * index / piece.letters.size
                val end = duration * (index + 1) / piece.letters.size
                val span = (end - start).coerceAtLeast(1)
                val letterPose = SpringLyricsMotion.pose(SpringLyricsMotion.Kind.Letter, elapsed - start, span, reduced)
                g.translate(0.0, letterPose.lift * size * effect * smoothstep((elapsed - start) / 75))
                emit(letter, SpringLyricsMotion.letterWipe((elapsed - start) / span), letterPose.glow * effect * .62)
                g.transform = wordTransform
            }
        }
        g.transform = parent
    }

    private fun springFill(glyph: GlyphSlice, progress: Double, dim: Double, bright: Double): java.awt.Paint {
        if (progress <= 0.0) return Color.WHITE.alpha(dim)
        if (progress >= 1.0) return Color.WHITE.alpha(bright)
        val edge = ((progress * 1.2 - .2) * glyph.width).toFloat()
        val start = if (glyph.rtl) glyph.left + glyph.width - edge else glyph.left + edge
        val end = start + glyph.width * if (glyph.rtl) -.20f else .20f
        return GradientPaint(start, 0f, Color.WHITE.alpha(bright), end, 0f, Color.WHITE.alpha(dim))
    }

    private fun paintDots(g: Graphics2D, box: LyricBox, elapsed: Double, edge: Double, width: Int, bloom: Boolean, light: HaloLight?) {
        val dot = box.dot ?: return
        val duration = (box.line.endMs - box.line.startMs).coerceAtLeast(1)
        val group = when {
            config.reduceMotion -> BloomMotion.DotGroup(1f, 1f)
            bloom -> BloomMotion.dotGroup(elapsed, duration)
            else -> BloomMotion.DotGroup(SpringLyricsMotion.dotGroup(elapsed, duration),
                smoothstep(elapsed / 140).toFloat())
        }
        if (group.scale < .01f || group.opacity < .01f) return
        val gap = if (bloom) (width * .0176f).coerceIn(.11f, 5.12f) else (width * .017f).coerceIn(.08f, 2.88f)
        val stride = dot.advance + gap
        val start = if (box.right) dot.right - 2 * stride - dot.advance else dot.left
        val center = start + stride + dot.advance / 2
        val y = box.inkHeight / 2
        val original = g.transform
        g.translate(center.toDouble(), y.toDouble())
        g.scale(group.scale.toDouble(), group.scale.toDouble())
        g.translate(-center.toDouble(), -y.toDouble())
        val groupTransform = g.transform
        val glyphBounds = dot.glyph.bounds2D
        for ((index, timing) in box.dotTimes.withIndex()) {
            val slot = if (box.right) 2 - index else index
            val pose = if (bloom) {
                val alpha = BloomMotion.dotOpacity(elapsed, duration, index)
                SpringLyricsMotion.Pose(1f, 0f, alpha * alpha * .56f, alpha)
            } else SpringLyricsMotion.pose(SpringLyricsMotion.Kind.Dot,
                elapsed + box.line.startMs - timing.first, timing.second - timing.first, config.reduceMotion)
            g.translate(start + slot * stride + glyphBounds.centerX, y + pose.lift * box.size.toDouble())
            val scale = pose.size.coerceIn(.01f, 1.5f).toDouble()
            g.scale(scale, scale)
            g.translate(-glyphBounds.centerX, -glyphBounds.centerY)
            val alpha = group.opacity * edge
            if (pose.glow > .022) light?.paint(g, dot.glyph, Color.WHITE.alpha(pose.glow * .9 * alpha))
            g.color = Color.WHITE.alpha(pose.opacity * alpha)
            g.fill(dot.glyph)
            g.transform = groupTransform
        }
        g.transform = original
    }

    private fun blurImage(source: BufferedImage, radius: Int): BufferedImage {
        val kernel = FloatArray(radius * 2 + 1) { index ->
            val x = (index - radius) / (radius * .53)
            exp(-.5 * x * x).toFloat()
        }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        val horizontal = ConvolveOp(Kernel(kernel.size, 1, kernel), ConvolveOp.EDGE_ZERO_FILL, null)
        val vertical = ConvolveOp(Kernel(1, kernel.size, kernel), ConvolveOp.EDGE_ZERO_FILL, null)
        return vertical.filter(horizontal.filter(source, null), null)
    }

    companion object {
        private val maskStops = floatArrayOf(0f, .15f, .4f, .7f, 1f)
        private val maskColors = arrayOf(Color.WHITE.alpha(0.0), Color.WHITE.alpha(.02), Color.WHITE.alpha(.32),
            Color.WHITE.alpha(.79), Color.WHITE)
    }
}
