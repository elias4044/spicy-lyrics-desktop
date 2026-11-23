package spicy.desktop

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSlider
import javax.swing.JToggleButton
import javax.swing.border.EmptyBorder
import javax.swing.plaf.basic.BasicMenuItemUI
import javax.swing.plaf.basic.BasicSliderUI
import kotlin.math.*

internal fun glyph(g: Graphics2D, name: String, x: Double, y: Double, size: Double, ink: Color) {
    val d = g.create() as Graphics2D
    d.translate(x - size / 2, y - size / 2)
    d.scale(size / 24, size / 24)
    d.stroke = BasicStroke(1.65f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    d.color = ink
    fun line(x1: Double, y1: Double, x2: Double, y2: Double) = d.draw(Line2D.Double(x1, y1, x2, y2))
    fun outline(vararg points: Double) = Path2D.Double().apply {
        moveTo(points[0], points[1])
        for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
    }
    fun transportTriangle(left: Double, top: Double, width: Double, height: Double) {
        val right = left + width
        val middle = top + height / 2
        val bottom = top + height
        d.fill(Path2D.Double().apply {
            moveTo(left, top + 1.7)
            quadTo(left, top - .4, left + 1.9, top + .7)
            lineTo(right - 1.5, middle - 1.2)
            quadTo(right + .5, middle, right - 1.5, middle + 1.2)
            lineTo(left + 1.9, bottom - .7)
            quadTo(left, bottom + .4, left, bottom - 1.7)
            closePath()
        })
    }
    when (name) {
        "wave" -> {
            d.stroke = BasicStroke(2.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for ((i, h) in listOf(5, 11, 18, 10, 5).withIndex()) {
                val at = 4.0 + i * 4
                line(at, 12.0 - h / 2.0, at, 12.0 + h / 2.0)
            }
        }
        "note" -> {
            d.fill(Ellipse2D.Double(3.0, 14.5, 7.0, 5.7))
            d.fill(Ellipse2D.Double(14.0, 12.0, 7.0, 5.7))
            d.draw(outline(9.0, 16.5, 9.0, 6.0, 20.0, 3.5, 20.0, 14.0))
            line(9.0, 9.0, 20.0, 6.5)
        }
        "play" -> transportTriangle(6.5, 3.0, 15.5, 18.0)
        "pause" -> {
            d.fill(rounded(6.2, 3.5, 4.5, 17.0, 2.5))
            d.fill(rounded(13.3, 3.5, 4.5, 17.0, 2.5))
        }
        "rewind", "advance", "previous", "next" -> {
            if (name == "rewind" || name == "previous") {
                d.translate(24.0, 0.0)
                d.scale(-1.0, 1.0)
            }
            transportTriangle(3.5, 4.5, 14.0, 15.0)
            d.fill(rounded(18.0, 4.5, 3.0, 15.0, 2.1))
        }
        "mini", "expand" -> {
            d.draw(rounded(3.0, 5.0, 18.0, 14.0, 3.5))
            if (name == "mini") d.fill(rounded(12.0, 12.0, 6.0, 4.0, 1.5))
            else d.draw(outline(9.0, 9.0, 15.0, 9.0, 15.0, 15.0))
        }
        "settings" -> {
            d.draw(Ellipse2D.Double(8.6, 8.6, 6.8, 6.8))
            val cog = Path2D.Double()
            for (i in 0 until 32) {
                val r = if (i % 4 in 1..2) 10.0 else 7.6
                val a = i * PI / 16 - PI / 32
                val gx = 12 + r * cos(a)
                val gy = 12 + r * sin(a)
                if (i == 0) cog.moveTo(gx, gy) else cog.lineTo(gx, gy)
            }
            cog.closePath()
            d.draw(cog)
        }
        "sources" -> {
            d.draw(rounded(3.0, 4.0, 18.0, 13.0, 3.7))
            line(8.0, 21.0, 16.0, 21.0)
            line(12.0, 17.0, 12.0, 21.0)
        }
        "lyrics" -> {
            line(4.5, 6.0, 19.5, 6.0)
            line(4.5, 12.0, 16.5, 12.0)
            line(4.5, 18.0, 13.0, 18.0)
            d.fill(Ellipse2D.Double(17.0, 16.0, 3.5, 3.5))
        }
        "sync" -> {
            d.draw(Ellipse2D.Double(3.5, 3.5, 17.0, 17.0))
            d.draw(outline(12.0, 7.0, 12.0, 12.0, 15.5, 14.0))
        }
        "info" -> {
            d.draw(Ellipse2D.Double(3.4, 3.4, 17.2, 17.2))
            line(12.0, 11.0, 12.0, 16.0)
            d.fill(Ellipse2D.Double(11.1, 7.1, 1.8, 1.8))
        }
        "external" -> {
            d.draw(outline(12.0, 5.0, 5.0, 5.0, 5.0, 19.0, 19.0, 19.0, 19.0, 12.0))
            d.draw(outline(14.0, 4.0, 20.0, 4.0, 20.0, 10.0))
            line(20.0, 4.0, 11.0, 13.0)
        }
        "folder" -> d.draw(outline(3.0, 6.0, 9.0, 6.0, 11.3, 8.0, 21.0, 8.0, 21.0, 18.5, 3.0, 18.5, 3.0, 6.0))
        "chevron" -> d.draw(outline(7.0, 10.0, 12.0, 15.0, 17.0, 10.0))
        "fullscreen" -> {
            d.draw(outline(3.5, 9.0, 3.5, 3.5, 9.0, 3.5))
            d.draw(outline(15.0, 3.5, 20.5, 3.5, 20.5, 9.0))
            d.draw(outline(20.5, 15.0, 20.5, 20.5, 15.0, 20.5))
            d.draw(outline(9.0, 20.5, 3.5, 20.5, 3.5, 15.0))
        }
        "check" -> d.draw(outline(5.0, 12.0, 10.0, 17.0, 19.0, 7.5))
        "refresh" -> {
            d.draw(Arc2D.Double(4.0, 4.0, 16.0, 16.0, 42.0, 297.0, Arc2D.OPEN))
            d.draw(outline(19.0, 3.5, 19.0, 9.0, 14.0, 9.0))
        }
        "up" -> d.draw(outline(6.0, 14.0, 12.0, 8.0, 18.0, 14.0))
        "down" -> d.draw(outline(6.0, 10.0, 12.0, 16.0, 18.0, 10.0))
        "minimize" -> line(5.0, 12.0, 19.0, 12.0)
        "maximize" -> d.draw(rounded(5, 5, 14, 14, 1))
        "restore" -> {
            d.draw(rounded(4, 8, 12, 12, 1))
            d.draw(outline(8.0, 5.0, 19.0, 5.0, 19.0, 16.0))
        }
        "close" -> {
            line(6.0, 6.0, 18.0, 18.0)
            line(18.0, 6.0, 6.0, 18.0)
        }
        "minus" -> line(5.0, 12.0, 19.0, 12.0)
        "plus" -> {
            line(5.0, 12.0, 19.0, 12.0)
            line(12.0, 5.0, 12.0, 19.0)
        }
    }
    d.dispose()
}

internal class PlayerButton(
    label: String,
    symbol: String,
    private val theme: () -> CoverTheme,
    val prominent: Boolean = false,
    val size: Int = 36,
    name: String = label.ifBlank { symbol.replaceFirstChar(Char::uppercaseChar) },
    val iconSize: Double = if (prominent && label.isBlank()) 24.0 else 17.0,
    val alignLeft: Boolean = false,
    action: () -> Unit,
) : JButton(label) {
    private val hoverMotion = MotionValue(this)
    private val pressMotion = MotionValue(this)
    private val selectionMotion = MotionValue(this)
    private val glyphMotion = MotionValue(this, 1.0)
    private var oldSymbol = symbol
    var symbol = symbol
        set(value) {
            if (field == value) return
            oldSymbol = field
            field = value
            glyphMotion.to(0.0, snap = true)
            glyphMotion.to(1.0, 155)
        }

    init {
        font = font(13, true)
        foreground = TEXT
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        toolTipText = name
        getAccessibleContext().accessibleName = name
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { action() }
        model.addChangeListener {
            hoverMotion.to(if (isEnabled && model.isRollover) 1.0 else 0.0, 180)
            pressMotion.to(if (isEnabled && model.isPressed) 1.0 else 0.0, if (model.isPressed) 90 else 165)
            selectionMotion.to(if (isSelected) 1.0 else 0.0, 230)
        }
    }

    override fun getPreferredSize(): Dimension {
        if (isPreferredSizeSet) return super.getPreferredSize()
        val label = text.orEmpty()
        val textWidth = if (label.isBlank()) 0 else getFontMetrics(font).stringWidth(label) + if (symbol.isBlank()) 26 else 46
        return Dimension(max(size, textWidth), size)
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.quality()
        val colors = theme()
        val enabled = isEnabled
        val empty = text.isNullOrBlank()
        val over = hoverMotion.value
        val down = pressMotion.value
        val selected = selectionMotion.value
        val w = width - 2
        val h = height - 2
        val round = if (prominent) h else 18
        val back = when {
            symbol == "close" && toolTipText == "Close" && over > 0 -> Color(195, 34, 51).alpha(over * (.65 + .20 * down))
            prominent && enabled -> mix(mix(colors.accent, Color.WHITE, .32 * over), Color.BLACK, .17 * down)
            prominent -> Color.WHITE.alpha(.09)
            enabled -> mix(Color.WHITE, colors.accent, selected).alpha(
                (if (empty) 0.0 else .067) + selected * .16 + over * .085 + down * .12)
            else -> Color.WHITE.alpha(.037)
        }
        val shape = rounded(1, 1, w, h, round)
        g.color = back
        g.fill(shape)
        if (isFocusOwner) {
            g.color = colors.accent
            g.stroke = BasicStroke(1.35f)
            g.draw(shape)
        }
        val ink = when {
            !enabled -> Color.WHITE.alpha(.28)
            prominent -> Color.BLACK
            else -> Color.WHITE
        }
        val fade = glyphMotion.value
        val iconX = if (empty) width / 2.0 else 18.5
        val iconY = height / 2.0
        val scale = 1.0 - down * .11
        if (oldSymbol.isNotEmpty() && fade < .995) {
            glyph(g, oldSymbol, iconX, iconY, iconSize * scale * (1 - .15 * fade), ink.alpha(ink.alpha / 255.0 * (1 - fade)))
        }
        if (symbol.isNotEmpty()) glyph(g, symbol, iconX, iconY, iconSize * scale * (.85 + .15 * fade), ink.alpha(ink.alpha / 255.0 * fade))
        if (!empty) {
            g.font = font
            g.color = ink
            val shift = if (symbol.isNotEmpty()) 32 else if (alignLeft) 13 else (width - g.fontMetrics.stringWidth(text)) / 2
            g.drawString(text, shift, (height + g.fontMetrics.ascent - g.fontMetrics.descent) / 2)
        }
        g.dispose()
    }
}

internal class SettingToggle(initial: Boolean, private val theme: () -> CoverTheme, action: (Boolean) -> Unit) : JToggleButton() {
    private val thumb = MotionValue(this, if (initial) 1.0 else 0.0)
    init {
        isSelected = initial
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { action(isSelected) }
        model.addChangeListener { thumb.to(if (isSelected) 1.0 else 0.0, 230) }
    }

    override fun getPreferredSize() = Dimension(42, 26)

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.quality()
        val on = thumb.value
        val x = width / 2.0 - 19
        val y = height / 2.0 - 10
        val track = rounded(x, y, 38, 20, 20)
        val shade = mix(Color.WHITE, theme().accent, on)
        g.color = shade.alpha(.13 + .87 * on)
        g.fill(track)
        g.color = if (hasFocus()) theme().muted else Color.WHITE.alpha(.13)
        g.draw(track)
        g.color = mix(Color.WHITE, Color.BLACK, on).alpha(.80 + .20 * on)
        g.fill(Ellipse2D.Double(x + 4 + 17 * on, y + 3, 14.0, 14.0))
        g.dispose()
    }
}

internal class PlayerMenu(private val theme: CoverTheme) : JPopupMenu() {
    private val surface = sheetColor(theme)
    private val reveal = RevealMotion(this)

    init {
        isOpaque = false
        background = surface
        border = EmptyBorder(6, 6, 6, 6)
    }

    override fun show(invoker: java.awt.Component, x: Int, y: Int) {
        super.show(invoker, x, y)
        reveal.enter()
    }

    override fun paint(graphics: Graphics) = reveal.paint(graphics) { super.paint(it) }

    override fun paintComponent(graphics: Graphics) {
        (graphics.create() as Graphics2D).useGraphics {
            it.quality()
            val shape = rounded(.5, .5, width - 1, height - 1, 16)
            it.color = surface
            it.fill(shape)
            it.color = theme.accent.alpha(.28)
            it.draw(shape)
        }
    }

    override fun addSeparator() {
        add(object : JComponent() {
            override fun getPreferredSize() = Dimension(1, 9)
            override fun paintComponent(g: Graphics) {
                g.color = theme.accent.alpha(.16)
                g.drawLine(7, 4, width - 7, 4)
            }
        })
    }

    fun heading(title: String, detail: String) {
        add(object : JComponent() {
            override fun getPreferredSize() = Dimension(263, 49)
            override fun paintComponent(graphics: Graphics) {
                (graphics.create() as Graphics2D).useGraphics {
                    it.quality()
                    it.font = font(15, true)
                    it.color = TEXT
                    it.drawString(title, 11, 19)
                    it.font = font(12)
                    it.color = MUTED
                    it.drawString(Stage.fit(it, detail, width - 22), 11, 38)
                }
            }
        })
        addSeparator()
    }

    fun choice(label: String, enabled: Boolean = true, symbol: String = "", action: () -> Unit) {
        add(object : JMenuItem(label) {
            override fun paintComponent(graphics: Graphics) {
                (graphics.create() as Graphics2D).useGraphics {
                    it.quality()
                    if (isEnabled && model.isArmed) {
                        it.color = theme.accent.alpha(.18)
                        it.fill(rounded(0, 1, width, height - 2, 10))
                    }
                    it.font = font
                    it.color = Color.WHITE.alpha(if (isEnabled) .98 else .40)
                    if (symbol.isNotEmpty()) glyph(it, symbol, 19.0, height / 2.0, 16.0,
                        theme.accent.alpha(if (isEnabled) .86 else .36))
                    val x = if (symbol.isEmpty()) 11 else 36
                    it.drawString(Stage.fit(it, text, width - x - 12), x,
                        (height + it.fontMetrics.ascent - it.fontMetrics.descent) / 2)
                }
            }
        }.apply {
            setUI(BasicMenuItemUI())
            isOpaque = false
            foreground = Color.WHITE
            font = font(13)
            border = EmptyBorder(8, if (symbol.isEmpty()) 11 else 36, 8, 14)
            isEnabled = enabled
            addActionListener { action() }
        })
    }
}

internal class PlaybackSlider(
    private val theme: () -> CoverTheme, min: Int = 0, max: Int = 1000, initial: Int = 0,
    private val persistentThumb: Boolean = false,
) : JSlider(min, max, initial) {
    private val progress = MotionValue(this, (initial - min) / (max - min).toDouble())
    init {
        setUI(TimelineLook(this))
        isOpaque = false
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Seek playback"
        getAccessibleContext().accessibleName = "Playback position"
        addChangeListener {
            val fraction = (value - minimum) / (maximum - minimum).coerceAtLeast(1).toDouble()
            progress.to(fraction, 82, valueIsAdjusting || abs(fraction - progress.value) > .055)
        }
    }

    override fun getPreferredSize() = Dimension(320, 22)

    private inner class TimelineLook(slider: JSlider) : BasicSliderUI(slider) {
        private var hovered = false
        override fun getThumbSize() = Dimension(13, 13)

        override fun paintTrack(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            g.quality()
            val over = hovered || slider.valueIsAdjusting
            val weight = if (persistentThumb) 4 else if (over && slider.isEnabled) 9 else 8
            val y = trackRect.y + (trackRect.height - weight) / 2
            val w = trackRect.width.coerceAtLeast(0)
            val track = rounded(trackRect.x, y, w, weight, weight)
            g.color = Color.BLACK.alpha(.16)
            g.fill(track)
            g.color = Color.WHITE.alpha(if (slider.isEnabled) .25 else .115)
            g.draw(track)
            val visualValue = slider.minimum + ((slider.maximum - slider.minimum) * progress.value).roundToInt()
            val done = (xPositionForValue(visualValue) - trackRect.x).coerceAtLeast(0)
            if (done > 0) {
                g.clip(track)
                g.color = Color.WHITE.alpha(if (slider.isEnabled) .94 else .44)
                g.fillRect(trackRect.x, y, done, weight)
            }
            g.dispose()
        }

        override fun paintThumb(graphics: Graphics) {
            if (!slider.isEnabled || !persistentThumb && !hovered && !slider.valueIsAdjusting && !slider.hasFocus()) return
            val g = graphics.create() as Graphics2D
            g.quality()
            val radius = if (persistentThumb) 6.5 else 5.0
            val x = thumbRect.centerX - radius
            val y = thumbRect.centerY - radius
            g.color = theme().accent
            g.fill(Ellipse2D.Double(x, y, radius * 2, radius * 2))
            g.dispose()
        }

        override fun paintFocus(g: Graphics) {
            val d = g.create() as Graphics2D
            d.quality()
            d.color = theme().muted
            d.draw(rounded(1, 1, slider.width - 3, slider.height - 3, 8))
            d.dispose()
        }

        override fun createTrackListener(slider: JSlider): TrackListener = object : TrackListener() {
            override fun mousePressed(event: MouseEvent) {
                if (!slider.isEnabled || event.button != MouseEvent.BUTTON1) return
                slider.requestFocusInWindow()
                calculateGeometry()
                slider.valueIsAdjusting = true
                slider.value = valueForXPosition(event.x)
            }

            override fun mouseDragged(event: MouseEvent) {
                if (slider.isEnabled && slider.valueIsAdjusting) slider.value = valueForXPosition(event.x)
            }

            override fun mouseReleased(event: MouseEvent) {
                if (slider.valueIsAdjusting) slider.valueIsAdjusting = false
                slider.repaint()
            }

            override fun mouseEntered(event: MouseEvent) { hovered = true; slider.repaint() }
            override fun mouseExited(event: MouseEvent) { hovered = false; slider.repaint() }
        }
    }
}

internal class ArtworkRoot(val scene: ArtworkScene, private val settings: () -> Settings, private val playing: () -> Boolean) :
    JPanel(BorderLayout()) {
    init { background = Color.BLACK }
    var themeChanged: () -> Unit = {}

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.quality()
        val previous = scene.theme
        scene.paint(g, width, height, settings(), playing())
        g.dispose()
        if (previous != scene.theme) themeChanged()
    }
}

internal class SoftPanel(private val lifted: Boolean = false) : JPanel(BorderLayout()) {
    init { isOpaque = false }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.quality()
        val shape = rounded(.5, .5, width - 1, height - 1, if (lifted) 24 else 17)
        g.color = if (lifted) Color.BLACK.alpha(.30) else Color.WHITE.alpha(.051)
        g.fill(shape)
        g.color = Color.WHITE.alpha(if (lifted) .115 else .085)
        g.draw(shape)
        g.dispose()
    }
}

internal fun playerMark(size: Int, theme: () -> CoverTheme): JComponent = object : JComponent() {
    override fun getPreferredSize() = Dimension(size, size)
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.quality()
        val colors = theme()
        val diameter = min(width, height) - 2.0
        val x = (width - diameter) / 2
        val y = (height - diameter) / 2
        val mark = rounded(x, y, diameter, diameter, diameter * .35)
        g.color = colors.accent.alpha(if (size > 45) .10 else .12)
        g.fill(mark)
        g.color = colors.accent.alpha(.26)
        g.draw(mark)
        glyph(g, "wave", x + diameter / 2, y + diameter / 2, diameter * .59, colors.accent)
        g.dispose()
    }
}
