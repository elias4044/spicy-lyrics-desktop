package spicy.desktop

import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.Paint
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import kotlin.math.ceil

internal class HaloLight(private val canvas: Graphics2D, private val projection: AffineTransform) {
    var painted = false
        private set

    fun paint(text: Graphics2D, shape: Shape, paint: Paint) {
        canvas.transform = AffineTransform(projection).apply { concatenate(text.transform) }
        canvas.paint = paint
        canvas.fill(shape)
        painted = true
    }
}

internal class LyricHalo {
    private var buffers: List<BufferedImage> = emptyList()
    private val weights = floatArrayOf(.05f, .105f, .21f, .27f, .21f, .105f, .05f)
    private val across = ConvolveOp(Kernel(weights.size, 1, weights))
    private val down = ConvolveOp(Kernel(1, weights.size, weights))

    fun begin(width: Int, height: Int): Graphics2D {
        val w = ceil(width * .32).toInt()
        val h = ceil(height * .32).toInt()
        if (buffers.firstOrNull()?.let { it.width == w && it.height == h } != true) {
            buffers = List(3) { BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE) }
        }
        return buffers[0].createGraphics().apply {
            composite = AlphaComposite.Clear
            fillRect(0, 0, w, h)
            composite = AlphaComposite.SrcOver
            quality()
            scale(w / width.toDouble(), h / height.toDouble())
        }
    }

    fun composite(canvas: Graphics2D, width: Int, height: Int) {
        across.filter(buffers[0], buffers[1])
        down.filter(buffers[1], buffers[2])
        canvas.composite = AlphaComposite.DstOver
        canvas.drawImage(buffers[2], 0, 0, width, height, null)
        canvas.composite = AlphaComposite.SrcOver
    }
}
