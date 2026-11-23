package spicy.desktop

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO

internal fun appIcon(size: Int): BufferedImage {
    val mark = playerMark(64) { CoverTheme.Neutral }
    mark.setSize(64, 64)
    return BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
        createGraphics().useGraphics {
            it.scale(size / 64.0, size / 64.0)
            mark.paint(it)
        }
    }
}

internal object LauncherIcon {
    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.single())
        val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
        val images = sizes.map { size ->
            ByteArrayOutputStream().apply { check(ImageIO.write(appIcon(size), "png", this)) }.toByteArray()
        }
        DataOutputStream(target.outputStream().buffered()).use { out ->
            // ICO directory fields are little-endian. Each entry points to a PNG image.
            out.writeShort(0)
            out.writeShort(java.lang.Short.reverseBytes(1).toInt())
            out.writeShort(java.lang.Short.reverseBytes(sizes.size.toShort()).toInt())
            var offset = 6 + 16 * sizes.size
            for ((size, image) in sizes.zip(images)) {
                out.writeByte(size % 256)
                out.writeByte(size % 256)
                out.writeShort(0)
                out.writeShort(java.lang.Short.reverseBytes(1).toInt())
                out.writeShort(java.lang.Short.reverseBytes(32).toInt())
                out.writeInt(Integer.reverseBytes(image.size))
                out.writeInt(Integer.reverseBytes(offset))
                offset += image.size
            }
            images.forEach { out.write(it) }
        }
    }
}
