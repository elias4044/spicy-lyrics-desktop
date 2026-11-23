package spicy.desktop

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.*

/** JDK implementation of the mobile Kawarp cover texture and domain warp. */
internal class CoverWarp(cover: BufferedImage) {
    private val texture = blurCover(cover)
    private val gridSize = 29
    private val gridX = FloatArray(gridSize * gridSize)
    private val gridY = FloatArray(gridX.size)
    private val sample = FloatArray(3)

    fun render(image: BufferedImage, phase: Double, strength: Float, scale: Float, blend: Float) {
        val w = image.width
        val h = image.height
        val output = (image.raster.dataBuffer as DataBufferInt).data
        buildGrid(phase.toFloat() * .05f, strength, scale)
        val horizontal = (gridSize - 1f) / max(1, w - 1)
        val vertical = (gridSize - 1f) / max(1, h - 1)
        for (y in 0 until h) {
            val gy = y * vertical
            val iy = gy.toInt().coerceAtMost(gridSize - 2)
            val fy = gy - iy
            val cy = y / max(1f, h - 1f) - .5f
            for (x in 0 until w) {
                val gx = x * horizontal
                val ix = gx.toInt().coerceAtMost(gridSize - 2)
                val fx = gx - ix
                val i = iy * gridSize + ix
                val u = bilinear(gridX, i, gridSize, fx, fy)
                val v = bilinear(gridY, i, gridSize, fx, fy)
                sampleTexture(u, v)
                val cx = x / max(1f, w - 1f) - .5f
                val vignette = (1f - .27f * (cx * cx + cy * cy)) * (1f - .13f * smooth(cy * 2f))
                val r = sample[0] * vignette
                val g = sample[1] * vignette
                val b = sample[2] * vignette
                val luma = .299f * r + .587f * g + .114f * b
                val sr = (luma + (r - luma) * 1.5f).coerceIn(0f, 1f)
                val sg = (luma + (g - luma) * 1.5f).coerceIn(0f, 1f)
                val sb = (luma + (b - luma) * 1.5f).coerceIn(0f, 1f)
                val cssLuma = .213f * sr + .715f * sg + .072f * sb
                // Tiny fixed dither keeps low-bit-depth gradients from banding when enlarged.
                val dither = (((x * 73 xor y * 151) and 15) - 7.5f) / 16
                fun channel(value: Float) =
                    ((cssLuma + (value - cssLuma) * 2.5f).coerceIn(0f, 1f) * 174 * blend + dither).roundToInt().coerceIn(0, 255)
                output[y * w + x] = channel(sr) shl 16 or (channel(sg) shl 8) or channel(sb)
            }
        }
    }

    private fun buildGrid(t: Float, strength: Float, scale: Float) {
        for (y in 0 until gridSize) for (x in 0 until gridSize) {
            val u = (.5f + (x / (gridSize - 1f) - .5f) / scale).coerceIn(0f, 1f)
            val v = (.5f + (.5f - y / (gridSize - 1f)) / scale).coerceIn(0f, 1f)
            val weight = (1 - smooth(hypot(u - .5f, v - .5f) / .7f)) * strength
            val n1 = noise(u * .35f + t, v * .35f + t * .7f)
            val n2 = noise(u * .35f - t * .8f + 50, v * .35f + t * .5f + 50)
            val n3 = noise(u * .9f + t * 1.2f + 100, v * .9f - t)
            val n4 = noise(u * .9f - t, v * .9f + t * 1.1f + 100)
            val i = y * gridSize + x
            gridX[i] = (u + (n1 * .65f + n3 * .35f) * weight).coerceIn(0f, 1f) * (SIZE - 1)
            gridY[i] = (v + (n2 * .65f + n4 * .35f) * weight).coerceIn(0f, 1f) * (SIZE - 1)
        }
    }

    private fun sampleTexture(u: Float, v: Float) {
        val x = u.toInt().coerceAtMost(SIZE - 2)
        val y = v.toInt().coerceAtMost(SIZE - 2)
        val index = (y * SIZE + x) * 3
        for (c in 0..2) {
            val a = texture[index + c]
            val b = texture[index + c + 3]
            val d = texture[index + c + SIZE * 3]
            val e = texture[index + c + SIZE * 3 + 3]
            val top = a + (b - a) * (u - x)
            val bottom = d + (e - d) * (u - x)
            sample[c] = top + (bottom - top) * (v - y)
        }
    }

    companion object {
        private const val SIZE = 128

        private fun blurCover(cover: BufferedImage): FloatArray {
            val downsample = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
            downsample.createGraphics().apply {
                quality()
                cropped(cover, 0, 0, SIZE, SIZE)
                dispose()
            }
            val packed = (downsample.raster.dataBuffer as DataBufferInt).data
            var read = FloatArray(packed.size * 3)
            var write = FloatArray(read.size)
            val horizontal = FloatArray(read.size)
            for ((i, pixel) in packed.withIndex()) {
                for (c in 0..2) read[i * 3 + c] = ((pixel shr (16 - c * 8)) and 255) / 255f
            }
            val limit = SIZE - 1
            val taps = IntArray(SIZE * 4)
            // Kawase's four diagonal half-pixel taps are separable at this resolution.
            repeat(8) { pass ->
                for (i in 0 until SIZE) {
                    taps[i * 4] = (i - pass - 1).coerceAtLeast(0)
                    taps[i * 4 + 1] = (i - pass).coerceAtLeast(0)
                    taps[i * 4 + 2] = (i + pass).coerceAtMost(limit)
                    taps[i * 4 + 3] = (i + pass + 1).coerceAtMost(limit)
                }
                for (y in 0 until SIZE) for (x in 0 until SIZE) {
                    val k = x * 4
                    val row = y * SIZE
                    for (c in 0..2) {
                        horizontal[(row + x) * 3 + c] = (read[(row + taps[k]) * 3 + c] +
                            read[(row + taps[k + 1]) * 3 + c] + read[(row + taps[k + 2]) * 3 + c] +
                            read[(row + taps[k + 3]) * 3 + c]) * .25f
                    }
                }
                for (y in 0 until SIZE) for (x in 0 until SIZE) {
                    val k = y * 4
                    for (c in 0..2) {
                        write[(y * SIZE + x) * 3 + c] = (horizontal[(taps[k] * SIZE + x) * 3 + c] +
                            horizontal[(taps[k + 1] * SIZE + x) * 3 + c] + horizontal[(taps[k + 2] * SIZE + x) * 3 + c] +
                            horizontal[(taps[k + 3] * SIZE + x) * 3 + c]) * .25f
                    }
                }
                val previous = read
                read = write
                write = previous
            }
            return read
        }

        private fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

        private fun bilinear(grid: FloatArray, i: Int, row: Int, x: Float, y: Float): Float {
            val top = grid[i] + (grid[i + 1] - grid[i]) * x
            val bottom = grid[i + row] + (grid[i + row + 1] - grid[i + row]) * x
            return top + (bottom - top) * y
        }

        // Scalar version of the mobile shader's 2D simplex noise.
        private fun noise(x: Float, y: Float): Float {
            val skew = (x + y) * .3660254f
            val i = floor(x + skew).toInt()
            val j = floor(y + skew).toInt()
            val unskew = (i + j) * .21132487f
            val dx = x - i + unskew
            val dy = y - j + unskew
            val ix = if (dx > dy) 1 else 0
            val iy = 1 - ix
            return 130f * (corner(i, j, dx, dy) +
                corner(i + ix, j + iy, dx - ix + .21132487f, dy - iy + .21132487f) +
                corner(i + 1, j + 1, dx - .57735027f, dy - .57735027f))
        }

        private fun corner(i: Int, j: Int, x: Float, y: Float): Float {
            val m = .5f - x * x - y * y
            if (m <= 0) return 0f
            fun permutation(n: Int): Int {
                val k = Math.floorMod(n, 289)
                return (34 * k + 1) * k % 289
            }
            val h = permutation(permutation(j) + i) / 41f
            val gradient = 2f * (h - floor(h)) - 1
            val gy = abs(gradient) - .5f
            val gx = gradient - floor(gradient + .5f)
            return m * m * m * m * (1.7928429f - .8537347f * (gx * gx + gy * gy)) * (gx * x + gy * y)
        }
    }
}
