package spicy.desktop

import java.awt.Color
import java.awt.AlphaComposite
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.*

internal fun Color.alpha(fraction: Double) = Color(red, green, blue, (fraction.coerceIn(0.0, 1.0) * 255).roundToInt())

internal fun mix(from: Color, to: Color, weight: Double): Color {
    fun channel(a: Int, b: Int) = (a + (b - a) * weight).roundToInt().coerceIn(0, 255)
    return Color(channel(from.red, to.red), channel(from.green, to.green), channel(from.blue, to.blue))
}

internal fun smoothstep(fraction: Double): Double {
    val x = fraction.coerceIn(0.0, 1.0)
    return x * x * (3 - 2 * x)
}

internal class CubicBezierEasing(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {
    private val samples = FloatArray(257) { index ->
        val x = index / 256f
        var low = 0f
        var high = 1f
        repeat(16) {
            val t = (low + high) / 2
            if (curve(t, x1, x2) < x) low = t else high = t
        }
        if (index == 0 || index == 256) x else curve((low + high) / 2, y1, y2)
    }

    fun transform(fraction: Float): Float {
        val index = fraction.coerceIn(0f, 1f) * 256
        val start = index.toInt().coerceAtMost(255)
        return samples[start] + (samples[start + 1] - samples[start]) * (index - start)
    }

    private fun curve(t: Float, a: Float, b: Float): Float {
        val u = 1 - t
        return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t
    }
}

internal data class CoverTheme(val base: Color, val wells: List<Color>, val accent: Color) {
    val panel get() = mix(base, Color.WHITE, .07)
    val edge get() = mix(base, Color.WHITE, .17)
    val muted get() = mix(base, Color.WHITE, .67)

    fun toward(to: CoverTheme, amount: Double) = CoverTheme(
        mix(base, to.base, amount), wells.zip(to.wells) { a, b -> mix(a, b, amount) }, mix(accent, to.accent, amount)
    )

    companion object {
        val Neutral = CoverTheme(Color.BLACK, List(3) { Color.BLACK }, Color.WHITE)

        fun from(cover: BufferedImage?): CoverTheme {
            cover ?: return Neutral
            val sample = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
            sample.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(cover, 0, 0, 48, 48, null)
                dispose()
            }
            val pixels = sample.getRGB(0, 0, 48, 48, null, 0, 48)
            val main = CoverColors.mainColor(pixels)
            val colors = CoverColors.of(pixels)
            val base = mix(Color.BLACK, Color(colors.higherContrast), .69)
            val wells = (0..2).map { index ->
                val region = sample.getRGB(0, index * 16, 48, 16, null, 0, 48)
                val color = mix(Color(main), Color(CoverColors.mainColor(region)), .64)
                Color(CoverColors.darkenTo(color.rgb, 5.0 + index))
            }
            val brightness = maxOf(Color(main).red, Color(main).green, Color(main).blue)
            val accent = if (brightness < 13) Color.WHITE else mix(Color(main), Color.WHITE, .68)
            return CoverTheme(base, wells, accent)
        }
    }
}

internal fun Graphics2D.quality() {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
}

internal fun Graphics2D.cropped(image: BufferedImage, x: Int, y: Int, w: Int, h: Int) {
    if (w <= 0 || h <= 0) return
    val scale = max(w.toDouble() / image.width, h.toDouble() / image.height)
    val iw = (image.width * scale).roundToInt()
    val ih = (image.height * scale).roundToInt()
    val layer = create(x, y, w, h) as Graphics2D
    layer.drawImage(image, (w - iw) / 2, (h - ih) / 2, iw, ih, null)
    layer.dispose()
}

internal class ArtworkScene {
    var theme = CoverTheme.Neutral
        private set
    private var previous = theme
    private var target = theme
    private var changedAt = 0L
    private var phase = 0.0
    private var frameAt = 0L
    private var motionRate = .1
    private var coverWarp: CoverWarp? = null
    private var backdrop: BufferedImage? = null
    private var outgoingBackdrop: BufferedImage? = null
    private var backdropTheme: CoverTheme? = null
    private var backdropMode = ""
    private var backdropAt = 0L

    fun setCover(cover: BufferedImage?) {
        target = CoverTheme.from(cover)
        if (backdropTheme != null) outgoingBackdrop = backdrop
        backdrop = null
        previous = theme
        changedAt = nowMs()
        backdropTheme = null
        coverWarp = cover?.let(::CoverWarp)
    }

    fun settlePalette(previewPhase: Double = 0.0) {
        changedAt = 0L
        phase = previewPhase
    }

    fun paint(g: Graphics2D, width: Int, height: Int, settings: Settings, playing: Boolean) {
        if (width <= 0 || height <= 0) return
        val now = nowMs()
        val t = if (settings.reduceMotion) 1.0 else smoothstep((now - changedAt) / 780.0)
        theme = if (t >= 1.0) target else previous.toward(target, t)
        val dt = ((now - frameAt) / 1000.0).coerceIn(0.0, .05)
        frameAt = now
        if (settings.background == "Solid") {
            g.color = theme.base
            g.fillRect(0, 0, width, height)
            return
        }
        val fade = t.toFloat()
        val old = outgoingBackdrop
        val warp = coverWarp
        if (t >= 1.0) outgoingBackdrop = null
        if (warp == null || fade < 1f) {
            if (old != null && fade < 1f) {
                g.drawImage(old, 0, 0, width, height, null)
            } else {
                g.color = Color.BLACK
                g.fillRect(0, 0, width, height)
            }
            if (warp == null) {
                if (old != null && fade < 1f) {
                    g.color = Color.BLACK.alpha(t)
                    g.fillRect(0, 0, width, height)
                }
                return
            }
        }
        val moving = settings.background == "Motion" && !settings.reduceMotion
        if (moving) {
            val goal = if (playing) 1.44 else .10
            motionRate += (goal - motionRate) * (1 - exp(-dt / .6))
            phase += dt * motionRate
        }
        val downsample = max(1.0, max(width, height) / 282.0)
        val sampleWidth = (width / downsample).roundToInt().coerceAtLeast(1)
        val sampleHeight = (height / downsample).roundToInt().coerceAtLeast(1)
        val previousSample = backdrop
        if (previousSample == null || previousSample.width != sampleWidth || previousSample.height != sampleHeight) {
            backdrop = BufferedImage(sampleWidth, sampleHeight, BufferedImage.TYPE_INT_RGB)
            backdropTheme = null
        }
        val image = checkNotNull(backdrop)
        val mode = settings.background + if (settings.reduceMotion) " still" else ""
        if (backdropMode != mode || backdropTheme == null ||
            (moving || backdropTheme != theme) && now - backdropAt >= 32) {
            val strength = if (settings.background == "Cover blur") 0f else 1f
            val zoom = (1.02 + .012 * min(1.0, motionRate)).toFloat()
            warp.render(image, if (moving) phase else 0.0, strength, zoom, 1f)
            backdropMode = mode
            backdropTheme = theme
            backdropAt = now
        }
        val composite = g.composite
        if (fade < 1f) g.composite = AlphaComposite.SrcOver.derive(fade)
        g.drawImage(image, 0, 0, width, height, null)
        g.composite = composite
    }

}

internal fun demoArtwork(): BufferedImage {
    val image = BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.quality()
    g.scale(512 / 300.0, 512 / 300.0)
    g.paint = GradientPaint(0f, 0f, Color(11, 21, 34), 300f, 300f, Color(37, 113, 123))
    g.fillRect(0, 0, 300, 300)
    g.paint = RadialGradientPaint(205f, 90f, 167f, floatArrayOf(0f, 1f),
        arrayOf(Color(121, 225, 204), Color(90, 180, 163, 0)))
    g.fillRect(0, 0, 300, 255)
    val rings = g.create() as Graphics2D
    rings.rotate(-.48, 155.0, 150.0)
    for (i in 0..22) {
        rings.color = Color(218, 255, 237, if (i % 4 == 0) 170 else 83)
        rings.drawOval(28 + 4 * i, 61 + 3 * i, 244 - 8 * i, 155 - 5 * i)
    }
    rings.dispose()
    g.color = Color(225, 255, 240)
    g.fillOval(174, 97, 51, 51)
    g.font = font(18, true)
    g.drawString("NIGHT SIGNALS", 22, 263)
    g.font = font(10)
    g.drawString("SPICY LYRICS DEMO", 23, 280)
    g.dispose()
    return image
}

internal fun rounded(x: Number, y: Number, w: Number, h: Number, diameter: Number) =
    RoundRectangle2D.Double(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), diameter.toDouble(), diameter.toDouble())
