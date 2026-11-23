package spicy.desktop

import java.awt.AlphaComposite
import java.awt.CardLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.math.abs

internal object UiMotion {
    var reduced = false
}

internal class MotionValue(private val owner: JComponent, initial: Double = 0.0) {
    private var from = initial
    private var goal = initial
    private var started = 0L
    private var duration = 1
    private val frames: Timer = Timer(15) {
        owner.repaint()
        if (!owner.isShowing || UiMotion.reduced || nowMs() - started >= duration) stop()
    }

    val value: Double
        get() {
            if (!owner.isShowing || UiMotion.reduced || from == goal) return goal
            val p = ((nowMs() - started) / duration.toDouble()).coerceIn(0.0, 1.0)
            val rest = 1 - p
            return goal + (from - goal) * rest * rest * rest
        }

    fun to(target: Double, millis: Int = 170, snap: Boolean = false) {
        if (target == goal && !snap) return
        from = value
        goal = target
        started = nowMs()
        duration = millis.coerceAtLeast(1)
        if (snap || UiMotion.reduced || !owner.isShowing || abs(from - goal) < .0001) {
            from = goal
            stop()
        } else frames.start()
        owner.repaint()
    }

    private fun stop() { frames.stop() }
}

internal class AnimatedCards : JPanel(CardLayout()) {
    private var previous: BufferedImage? = null
    private var current: String? = null
    private val motion = MotionValue(this, 1.0)

    init { isOpaque = false }

    override fun removeAll() {
        previous = null
        current = null
        super.removeAll()
    }

    fun showCard(name: String) {
        if (name == current) return
        if (isShowing && width > 0 && height > 0 && current != null && !UiMotion.reduced) {
            previous = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE).also {
                it.createGraphics().useGraphics { g -> paint(g) }
            }
        } else previous = null
        current = name
        (layout as CardLayout).show(this, name)
        if (previous != null) {
            motion.to(0.0, snap = true)
            motion.to(1.0, 230)
        }
        repaint()
    }

    override fun paintChildren(graphics: Graphics) {
        val image = previous
        val p = motion.value
        if (image == null || p >= .999) {
            previous = null
            super.paintChildren(graphics)
            return
        }
        (graphics.create() as Graphics2D).useGraphics { g ->
            g.composite = AlphaComposite.SrcOver.derive((1 - p).toFloat())
            g.drawImage(image, (-10 * p).toInt(), 0, null)
            g.composite = AlphaComposite.SrcOver.derive(p.toFloat())
            super.paintChildren(g)
        }
    }
}

internal class RevealMotion(owner: JComponent) {
    private val value = MotionValue(owner, 1.0)

    fun enter() {
        value.to(0.0, snap = true)
        value.to(1.0, 195)
    }

    fun paint(graphics: Graphics, content: (Graphics) -> Unit) {
        val p = value.value
        if (p >= .999) {
            content(graphics)
            return
        }
        (graphics.create() as Graphics2D).useGraphics {
            it.composite = AlphaComposite.SrcOver.derive((.28 + p * .72).toFloat())
            it.translate(0.0, (p - 1) * 6)
            content(it)
        }
    }
}
