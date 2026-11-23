package spicy.desktop

import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsDevice
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.RootPaneContainer
import java.awt.FlowLayout
import java.awt.geom.RoundRectangle2D

/** Shared title bar behavior for the player and its dialogs. */
internal class WindowChrome(
    private val window: Window,
    private val theme: () -> CoverTheme,
    resizable: Boolean = true,
    private val canMaximize: () -> Boolean = { true },
) {
    private val frame = window as? Frame
    private var restoreBounds: Rectangle? = null
    var fullscreen = false
        set(value) { field = value; buttons.isVisible = !value; updateShape(); edge.repaint() }
    val maximized get() = restoreBounds != null || frame?.let { it.extendedState and Frame.MAXIMIZED_BOTH != 0 } == true
    private val maximize = caption("maximize", "Maximize") { toggleMaximized() }
    val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
        isOpaque = false
        if (frame != null) {
            add(caption("minimize", "Minimize") { frame.extendedState = frame.extendedState or Frame.ICONIFIED })
            add(maximize)
        }
        add(caption("close", "Close") { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) })
    }
    private val edge = ResizeBorder()

    init {
        if (resizable) (window as RootPaneContainer).glassPane = edge.apply { isVisible = true }
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = updateShape()
            override fun componentShown(event: ComponentEvent) = updateShape()
        })
        frame?.addWindowStateListener { updateShape() }
    }

    private fun updateShape() {
        if (window.width <= 0 || window.height <= 0) return
        if (!window.graphicsConfiguration.device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)) return
        val rounded = !fullscreen && !maximized && window.graphicsConfiguration.device.fullScreenWindow != window
        runCatching {
            window.shape = if (rounded) RoundRectangle2D.Double(0.0, 0.0, window.width.toDouble(), window.height.toDouble(), 26.0, 26.0)
            else null
        }
    }

    private fun caption(icon: String, label: String, action: () -> Unit) =
        PlayerButton("", icon, theme, size = 35, name = label, action = action).apply {
            preferredSize = Dimension(40, 35)
        }

    fun refresh() {
        maximize.symbol = if (maximized) "restore" else "maximize"
        val name = if (maximized) "Restore window" else "Maximize"
        maximize.toolTipText = name
        maximize.accessibleContext.accessibleName = name
        maximize.isEnabled = canMaximize()
        updateShape()
        edge.repaint()
    }

    fun toggleMaximized(point: Point = Point(window.x + window.width / 2, window.y + 25)) {
        val host = frame ?: return
        if (fullscreen || !canMaximize()) return
        val old = restoreBounds
        when {
            old != null -> {
                restoreBounds = null
                window.bounds = old
            }
            host.extendedState and Frame.MAXIMIZED_BOTH != 0 -> host.extendedState = Frame.NORMAL
            else -> {
                restoreBounds = Rectangle(window.bounds)
                window.bounds = availableScreen(point)
            }
        }
        refresh()
    }

    fun dragFrom(vararg components: Component) {
        val move = object : MouseAdapter() {
            private var origin: Point? = null
            private var within = Point()
            private var moved = false

            override fun mousePressed(e: MouseEvent) {
                if (fullscreen || e.button != MouseEvent.BUTTON1) return
                val at = e.locationOnScreen
                origin = at
                within = Point(at.x - window.x, at.y - window.y)
                moved = false
            }

            override fun mouseDragged(e: MouseEvent) {
                val first = origin ?: return
                val at = e.locationOnScreen
                if (!moved && first.distance(at) < 4) return
                moved = true
                if (maximized && frame != null) {
                    val ratio = (within.x / window.width.toDouble()).coerceIn(0.0, 1.0)
                    toggleMaximized(at)
                    within.x = (window.width * ratio).toInt()
                }
                window.setLocation(at.x - within.x, at.y - within.y)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (origin != null && moved && frame != null && !maximized && canMaximize()) {
                    val at = e.locationOnScreen
                    if (at.y <= availableScreen(at).y + 4) toggleMaximized(at)
                }
                origin = null
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 2) toggleMaximized(e.locationOnScreen)
            }
        }
        for (component in components) {
            component.addMouseListener(move)
            component.addMouseMotionListener(move)
        }
    }

    private fun availableScreen(point: Point): Rectangle {
        val screens = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        val device = screens.firstOrNull { it.defaultConfiguration.bounds.contains(point) }
        val config = device?.defaultConfiguration ?: window.graphicsConfiguration
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        return Rectangle(config.bounds).apply {
            x += insets.left
            y += insets.top
            width -= insets.left + insets.right
            height -= insets.top + insets.bottom
        }
    }

    private inner class ResizeBorder : JComponent() {
        private var anchor: Point? = null
        private var original = Rectangle()
        private var sides = 0

        init {
            isOpaque = false
            val resize = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    cursor = Cursor.getPredefinedCursor(resizeCursor(hit(e.x, e.y)))
                }
                override fun mousePressed(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1) return
                    sides = hit(e.x, e.y)
                    if (sides == 0) return
                    original = Rectangle(window.bounds)
                    anchor = e.locationOnScreen
                }
                override fun mouseReleased(e: MouseEvent) { anchor = null }
                override fun mouseDragged(e: MouseEvent) {
                    val from = anchor ?: return
                    val at = e.locationOnScreen
                    val dx = at.x - from.x
                    val dy = at.y - from.y
                    val minimum = window.minimumSize
                    val box = Rectangle(original)
                    if (sides and LEFT != 0) {
                        box.width = (original.width - dx).coerceAtLeast(minimum.width)
                        box.x = original.x + original.width - box.width
                    }
                    if (sides and RIGHT != 0) box.width = (original.width + dx).coerceAtLeast(minimum.width)
                    if (sides and TOP != 0) {
                        box.height = (original.height - dy).coerceAtLeast(minimum.height)
                        box.y = original.y + original.height - box.height
                    }
                    if (sides and BOTTOM != 0) box.height = (original.height + dy).coerceAtLeast(minimum.height)
                    window.bounds = box
                }
            }
            addMouseListener(resize)
            addMouseMotionListener(resize)
        }

        override fun contains(x: Int, y: Int) = !fullscreen && !maximized && (anchor != null || hit(x, y) != 0)

        override fun paintComponent(graphics: Graphics) {
            if (fullscreen || maximized || width < 1 || height < 1) return
            val g = graphics.create() as Graphics2D
            g.quality()
            g.color = theme().accent.alpha(.20)
            g.drawRoundRect(0, 0, width - 1, height - 1, 26, 26)
            g.dispose()
        }

        private fun hit(x: Int, y: Int): Int {
            if (x !in 0 until width || y !in 0 until height) return 0
            var direction = 0
            if (x < 6 || y < 6 && x < 14 || y >= height - 6 && x < 14) direction = direction or LEFT
            if (x >= width - 6 || y < 6 && x >= width - 14 || y >= height - 6 && x >= width - 14) direction = direction or RIGHT
            if (y < 6) direction = direction or TOP
            if (y >= height - 6) direction = direction or BOTTOM
            return direction
        }

        private fun resizeCursor(sides: Int): Int = when (sides) {
            TOP or LEFT -> Cursor.NW_RESIZE_CURSOR
            TOP or RIGHT -> Cursor.NE_RESIZE_CURSOR
            BOTTOM or LEFT -> Cursor.SW_RESIZE_CURSOR
            BOTTOM or RIGHT -> Cursor.SE_RESIZE_CURSOR
            TOP -> Cursor.N_RESIZE_CURSOR
            BOTTOM -> Cursor.S_RESIZE_CURSOR
            LEFT -> Cursor.W_RESIZE_CURSOR
            RIGHT -> Cursor.E_RESIZE_CURSOR
            else -> Cursor.DEFAULT_CURSOR
        }
    }

    companion object {
        private const val LEFT = 1
        private const val RIGHT = 2
        private const val TOP = 4
        private const val BOTTOM = 8
    }
}
