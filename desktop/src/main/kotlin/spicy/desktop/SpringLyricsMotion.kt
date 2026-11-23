package spicy.desktop

import kotlin.math.ceil
import kotlin.math.sin

/**
 * Spring curves from the Android animator. Sampling on the lyric clock keeps the motion
 * consistent through seeks, pauses and irregular Swing repaint intervals.
 */
internal object SpringLyricsMotion {
    enum class Kind { Word, Letter, Dot }
    data class Pose(val size: Float, val lift: Float, val glow: Float, val opacity: Float)

    private data class Curves(
        val size: CubicSplineInterpolator, val lift: CubicSplineInterpolator,
        val glow: CubicSplineInterpolator, val opacity: CubicSplineInterpolator,
    )

    private const val STEP_MS = 1000f / 120
    private const val TAIL_MS = 1_600
    private fun curve(vararg pairs: Pair<Float, Float>) = CubicSplineInterpolator(pairs.map { AnimationPoint(it.first, it.second) })
    private val wordGlow = curve(0f to 0f, .15f to 1f, .6f to 1f, 1f to 0f)
    private val opaque = curve(0f to 1f, 1f to 1f)
    private val curves = mapOf(
        Kind.Word to Curves(opaque,
            curve(0f to .01f, .9f to -1f / 60, 1f to 0f), wordGlow, opaque),
        Kind.Letter to Curves(opaque,
            curve(0f to .01f, .9f to -1f / 56, 1f to 0f), wordGlow, opaque),
        Kind.Dot to Curves(curve(0f to .75f, .7f to 1.05f, 1f to 1f),
            curve(0f to 0f, .9f to -.12f, 1f to 0f),
            curve(0f to 0f, .6f to 1f, 1f to 1f), curve(0f to .35f, .6f to 1f, 1f to 1f)),
    )
    private data class Key(val kind: Kind, val durationMs: Int)
    private val samples = object : LinkedHashMap<Key, FloatArray>(192, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, FloatArray>) = size > 192
    }

    fun pose(kind: Kind, elapsed: Double, duration: Long, reduce: Boolean): Pose {
        val k = curves.getValue(kind)
        val clipped = duration.coerceIn(1L, 30_000).toInt()
        val time = elapsed * clipped / duration.coerceAtLeast(1L)
        val progress = (elapsed / duration.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
        if (reduce) return Pose(1f, 0f, if (kind == Kind.Dot) k.glow.at(progress) else 0f, k.opacity.at(progress))
        if (elapsed <= 0 || time >= clipped + TAIL_MS) {
            val p = if (elapsed <= 0) 0f else 1f
            return Pose(k.size.at(p), k.lift.at(p), k.glow.at(p), k.opacity.at(p))
        }
        val table = synchronized(samples) { samples.getOrPut(Key(kind, clipped)) { bake(kind, k, clipped) } }
        val step = (time / STEP_MS).coerceIn(0.0, (table.size / 4 - 1).toDouble())
        val lo = step.toInt()
        val hi = (lo + 1).coerceAtMost(table.size / 4 - 1)
        val t = (step - lo).toFloat()
        fun channel(channel: Int): Float {
            val a = table[lo * 4 + channel]
            return a + (table[hi * 4 + channel] - a) * t
        }
        return Pose(channel(0), channel(1), channel(2), channel(3))
    }

    private fun bake(kind: Kind, c: Curves, duration: Int): FloatArray {
        val dots = kind == Kind.Dot
        val scale = SpringSimulation(c.size.at(0f), if (dots) .70f else .88f, if (dots) .60f else .64f)
        val lift = SpringSimulation(c.lift.at(0f), if (dots) 1.25f else 1.45f, .40f)
        val glow = SpringSimulation(c.glow.at(0f), if (dots) 1f else 1.18f, if (dots) .50f else .56f)
        val opacity = SpringSimulation(c.opacity.at(0f), 1f, .50f)
        val count = ceil((duration + TAIL_MS) / STEP_MS).toInt() + 1
        val result = FloatArray(count * 4)
        for (i in 0 until count) {
            val p = (i * STEP_MS / duration).coerceAtMost(1f)
            val dt = if (i == 0) 0f else STEP_MS / 1_000
            scale.setGoal(c.size.at(p))
            lift.setGoal(c.lift.at(p))
            glow.setGoal(c.glow.at(p))
            opacity.setGoal(c.opacity.at(p))
            result[i * 4] = scale.step(dt)
            result[i * 4 + 1] = lift.step(dt)
            result[i * 4 + 2] = glow.step(dt)
            result[i * 4 + 3] = opacity.step(dt)
        }
        return result
    }

    private val expand = CubicBezierEasing(.37f, 0f, .63f, 1f)
    private val collapse = floatArrayOf(0f, 0f, .094f, -.006f, .18f, -.029f, .433f, -.157f, .514f, -.185f,
        .559f, -.189f, .6f, -.182f, .639f, -.163f, .676f, -.133f, .723f, -.074f, .767f, .006f,
        .85f, .238f, .927f, .566f, 1f, 1f)

    fun dotGroup(elapsed: Double, duration: Long): Float {
        val leaving = (elapsed - (duration - 500)) / 400.0
        if (leaving <= 0) return expand.transform((elapsed / 300.0).toFloat())
        if (leaving >= 1) return 0f
        var i = 0
        while (i < collapse.size - 4 && leaving > collapse[i + 2]) i += 2
        val t = ((leaving - collapse[i]) / (collapse[i + 2] - collapse[i])).toFloat()
        return 1f - (collapse[i + 1] + t * (collapse[i + 3] - collapse[i + 1]))
    }

    fun letterWipe(p: Double): Double = sin(p.coerceIn(0.0, 1.0) * Math.PI / 2)
}
