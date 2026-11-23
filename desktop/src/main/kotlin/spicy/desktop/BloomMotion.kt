package spicy.desktop

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Bloom look, worked out from the time alone so a seek lands on the right pose. Times are
 * milliseconds; lengths are em of the text's own size unless named otherwise.
 *
 * Every line rests at one dim tone and the part being sung lights up behind one soft edge that
 * runs along the whole line. Words lift on a stiff spring as they start; held words lift and
 * glow letter by letter. The list follows the song on a timed, staggered scroll.
 */
internal object BloomMotion {
    /** The space between two lines, in em of the lyric size. */
    const val GAP_EM = 0.8f
    /** Spring's line gap: a quarter tighter than Bloom's. */
    const val SPRING_GAP_EM = GAP_EM * 0.75f
    /** The lyrics run this much behind the song's clock. */
    const val POSITION_SHIFT_MS = 33.5

    /** The part being sung, and everything else (lines at rest, the part still to come). */
    const val LIT = 0.94f
    const val UNLIT = 0.18f
    /** Background vocals light up less. */
    const val BACKGROUND_LIT = 0.35f

    /** Lines at rest are this size; the line being sung grows to full size from its bottom leading corner. */
    const val INACTIVE_SCALE = 0.98f
    const val SCALE_IN_MS = 500
    const val SCALE_IN_DELAY_MS = 150
    const val SCALE_OUT_MS = 350
    const val SCALE_OUT_DELAY_MS = 50
    val SCALE_EASE = CubicBezierEasing(0.4f, 0.1f, 0f, 1f)

    /** A finished line dims back to rest a moment after it ends. */
    const val DIM_MS = 350
    const val DIM_DELAY_MS = 250
    val DIM_EASE = CubicBezierEasing(0.39f, 0.575f, 0.565f, 1f)
    /** A line-synced line lights up and dims as a whole: up quicker than down. */
    const val LINE_LIGHT_MS = 300
    const val LINE_DIM_MS = 400
    val LINE_TONE_EASE = CubicBezierEasing(0.33f, 0f, 0.2f, 1f)

    // ── The fill ────────────────────────────────────────────────────────────────────────────

    /** The width of the fill's soft edge, for words and letters alike. */
    const val FADE_EM = 0.882f
    /** The space between words, when a line has no two on one row to measure it from. */
    const val DEFAULT_GAP_EM = 0.19f
    /** Once a row's last word is sung, the rest of the row lights up over this long. */
    const val RUSH_MS = 500.0

    /**
     * How far past a word the far end of the fade reaches while it's sung, as a share of the fade.
     * The last word of a row takes it past its end; a held word split into letters keeps it close.
     */
    fun reach(letters: Int, split: Boolean, rowEnd: Boolean): Float = when {
        split -> if (letters <= 2) 0.25f else 0.12f
        rowEnd -> 1f
        letters <= 2 -> 0.5f
        else -> 0.25f
    }

    /** One word on the line's strip: where it starts, how wide its box is (the space after it included). */
    class Span(val x: Float, val w: Float, val reach: Float, val rowStart: Boolean, val rowEnd: Boolean)

    /**
     * Where the fill's edge (the middle of the fade) is for each word at [t], in px along the
     * line laid end to end; -infinity where no light has reached. [paced] gives a word's own
     */
    fun sweepEdges(
        spans: Array<Span?>, starts: LongArray, ends: LongArray, fade: Float, t: Double,
    ): DoubleArray {
        val edges = DoubleArray(spans.size) { Double.NEGATIVE_INFINITY }
        val half = fade / 2.0
        // Each word starts the fade wholly before it (every row starts dim) or where the word
        // before it left it, and takes its far end [Span.reach] past the word.
        var reached = Double.NEGATIVE_INFINITY
        var carried = Double.NEGATIVE_INFINITY
        for (i in spans.indices) {
            val span = spans[i] ?: continue
            val start = span.x - half
            val from = if (span.rowStart) start else maxOf(start, carried)
            val to = maxOf(from, span.x + span.w + fade * span.reach - half)
            carried = to
            var at = Double.NEGATIVE_INFINITY
            if (t >= ends[i]) {
                at = to
                // The end of a row lights up the rest of it.
                val lit = span.x + span.w + half
                if (span.rowEnd && to < lit) at = to + (lit - to) * minOf(1.0, (t - ends[i]) / RUSH_MS)
            } else if (t > starts[i]) {
                val share = (t - starts[i]) / (ends[i] - starts[i]).coerceAtLeast(1L)
                at = from + (to - from) * share
            }
            edges[i] = at
            if (at > reached) reached = at
        }
        // A word being sung shows its own edge, so two words sung across each other both fill. One
        // still to come is lit only by the words before it on its row, never by a later one sung
        // early. One already sung follows the light wherever it is.
        var before = Double.NEGATIVE_INFINITY
        for (i in spans.indices) {
            val span = spans[i] ?: continue
            if (span.rowStart) before = Double.NEGATIVE_INFINITY
            val at = edges[i]
            if (t >= ends[i]) edges[i] = reached else if (t <= starts[i]) edges[i] = before
            if (at > before) before = at
        }
        return edges
    }

    /** The fill position of a box at [x], [width] wide, for an [edge]: -50 fully dim, 100 fully lit. */
    fun gradientPosition(edge: Double, x: Float, width: Float, fade: Float): Float {
        if (edge == Double.NEGATIVE_INFINITY) return -50f
        val rest = (width + fade / 2.0 - (edge - x)) / (width + fade)
        return (100.0 - 150.0 * rest).coerceIn(-50.0, 100.0).toFloat()
    }

    /** A split letter's tone for its fill position; its glow dims with it. */
    fun letterTone(gradient: Float): Float = UNLIT + (LIT - UNLIT) * ((gradient + 50f) / 150f).coerceIn(0f, 1f)

    /**
     * The edge of the bright part and the start of the dim part, as shares of a box [width] px
     * wide, for a fill [position] (-50..100) and a soft edge [fade] px wide.
     */
    fun wipeStops(position: Float, width: Float, fade: Float): Pair<Float, Float> {
        val w = width.coerceAtLeast(1f)
        val start = (position + 50f) / 150f * (w + fade) - fade
        return start / w to (start + fade) / w
    }

    // ── Lift ────────────────────────────────────────────────────────────────────────────────

    /** How high a sung word (or letter) stands, in em. */
    const val LIFT_EM = 0.0588f
    private const val LIFT_OMEGA = 5.0
    private const val LIFT_DAMPING = 0.93

    /** 0 to 1: how far a lift [elapsedMs] old has come, on a stiff, barely underdamped spring. */
    fun lift(elapsedMs: Double): Double {
        if (elapsedMs <= 0) return 0.0
        val t = elapsedMs / 1000
        val decay = exp(-LIFT_DAMPING * LIFT_OMEGA * t)
        if (decay < 1e-4) return 1.0
        val wd = LIFT_OMEGA * sqrt(1 - LIFT_DAMPING * LIFT_DAMPING)
        return 1 - decay * (cos(wd * t) + (LIFT_DAMPING * LIFT_OMEGA / wd) * sin(wd * t))
    }

    // ── Held words ──────────────────────────────────────────────────────────────────────────

    // Each letter lifts and glows in turn. The duration of the word sets the stagger and glow timing.
    private const val STAGGER = 0.4
    private const val MAX_STAGGER_MS = 400.0
    private const val MAX_PHASE_MS = 3000.0
    /** The glow's peak opacity. */
    const val GLOW_ALPHA = 0.5f
    /** The glow's blur radius: 0.17em plus one px of the 56px desktop lyrics. */
    const val GLOW_RADIUS_EM = 0.17f + 1f / 56f
    private val emphasisEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    private fun ease(x: Double) = emphasisEase.transform(x.coerceIn(0.0, 1.0).toFloat()).toDouble()

    /**
     * Every letter of a held word at one moment. [rest] is set when all of them are at rest,
     * not started (0) or settled and lifted (1); the arrays are then empty.
     */
    class Flame(
        val rest: Int?,
        val y: FloatArray = FloatArray(0),
        val glow: FloatArray = FloatArray(0),
    ) {
        fun y(i: Int) = y.getOrElse(i) { -LIFT_EM * (rest ?: 0) }
        fun glow(i: Int) = glow.getOrElse(i) { 0f }
    }

    private val BEFORE = Flame(0)
    private val AFTER = Flame(1)

    /**
     * A held word's [count] letters [elapsed] ms after its first letter starts, for a word
     * [duration] ms long.
     */
    fun flame(elapsed: Double, duration: Long, count: Int): Flame {
        val n = maxOf(1, count)
        val du = maxOf(1L, duration).toDouble()
        val phase = minOf(du, MAX_PHASE_MS)
        val stagger = minOf(STAGGER * du / n, MAX_STAGGER_MS)
        val holdMs = 2 * du / n
        val held = ease(minOf(1.0, holdMs / phase))
        if (elapsed <= 0) return BEFORE
        val lastAt = elapsed - (n - 1) * stagger
        if (lastAt >= holdMs + phase && lift(lastAt) == 1.0) return AFTER

        val glow = FloatArray(n)
        val y = FloatArray(n)
        for (i in 0 until n) {
            val at = elapsed - i * stagger
            val amount = when {
                at > 0 && at < holdMs -> ease(minOf(1.0, at / phase))
                at >= holdMs -> held * (1 - ease(minOf(1.0, (at - holdMs) / phase)))
                else -> 0.0
            }
            val lifted = lift(at)
            glow[i] = (GLOW_ALPHA * amount).toFloat()
            y[i] = (-LIFT_EM * lifted).toFloat()
        }

        return Flame(null, y, glow)
    }

    // ── Interlude ───────────────────────────────────────────────────────────────────────────

    // The dots fade in one after another, then fill from dim to lit in turn, each over its own
    // share of the interlude; the last finishes as the group leaves. The group breathes in whole
    // cycles of at least four seconds about its middle. To leave it swells, then shrinks and
    // fades away, finishing as the line closes.
    private const val DOT_DIM = 0.18
    private const val DOT_LIT = 0.94
    private const val DOT_ENTER_MS = 750.0
    private const val DOT_ENTER_STAGGER_MS = 50.0
    private const val BREATH_MS = 4000.0
    private const val BREATH_SCALE = 0.2
    private const val EXIT_GROW_MS = 750.0
    private const val EXIT_SHRINK_MS = 250.0
    private const val EXIT_SCALE = 0.5
    private val dotFillEase = CubicBezierEasing(0f, 0.25f, 1f, 0.58f)
    private val dotExitEase = CubicBezierEasing(0.25f, 0f, 1f, 0.2f)
    private val dotBreathEase = CubicBezierEasing(0.5f, 0.02f, 0.42f, 0.98f)
    private fun CubicBezierEasing.at(x: Double) = transform(x.coerceIn(0.0, 1.0).toFloat()).toDouble()

    /** When the group starts to leave: [closeMs] is how long before the line's end it closes. */
    private fun exitAt(duration: Long, closeMs: Long) = maxOf(0.0, (duration - closeMs - EXIT_GROW_MS))

    /** Dot [index] of [count]'s opacity [elapsed] ms into an interlude [duration] ms long. */
    fun dotOpacity(elapsed: Double, duration: Long, index: Int, count: Int = 3, closeMs: Long = 500): Float {
        val n = maxOf(1, count)
        val exitAt = exitAt(duration, closeMs)
        val share = (exitAt + EXIT_GROW_MS) / n
        val fill = if (index < n - 1) {
            dotFillEase.at((elapsed - index * share) / share)
        } else {
            // The last dot gets what's left before the exit, then fills the rest evenly while
            // the group swells.
            val from = index * share
            val room = maxOf(1.0, exitAt - from)
            val reached = minOf(1.0, room / share)
            if (elapsed < exitAt) reached * dotFillEase.at((elapsed - from) / room)
            else reached + (1 - reached) * ((elapsed - exitAt) / EXIT_GROW_MS).coerceIn(0.0, 1.0)
        }
        val enter = ((elapsed - index * DOT_ENTER_STAGGER_MS) / DOT_ENTER_MS).coerceIn(0.0, 1.0).let { it * it }
        return ((DOT_DIM + (DOT_LIT - DOT_DIM) * fill) * enter).toFloat()
    }

    /** The dot group's breathing scale (about its middle) and its opacity as it leaves. */
    data class DotGroup(val scale: Float, val opacity: Float)

    private fun breath(x: Double): Double = if (x < 0.5) 1 + BREATH_SCALE * dotBreathEase.at(x * 2)
        else 1 + BREATH_SCALE - BREATH_SCALE * dotBreathEase.at((x - 0.5) * 2)

    fun dotGroup(elapsed: Double, duration: Long, closeMs: Long = 500): DotGroup {
        val exitAt = exitAt(duration, closeMs)
        fun breathScale(at: Double): Double {
            val cycles = floor(exitAt / BREATH_MS)
            if (cycles < 1) return 1.0
            val length = exitAt / cycles
            return breath((at.coerceIn(0.0, exitAt) % length) / length)
        }
        return when {
            elapsed < exitAt -> DotGroup(breathScale(elapsed).toFloat(), 1f)
            elapsed < exitAt + EXIT_GROW_MS -> {
                val from = breathScale(exitAt)
                val top = 1 + BREATH_SCALE
                DotGroup((from + (top - from) * ease((elapsed - exitAt) / EXIT_GROW_MS)).toFloat(), 1f)
            }
            else -> {
                val x = dotExitEase.at((elapsed - exitAt - EXIT_GROW_MS) / EXIT_SHRINK_MS)
                DotGroup((1 + BREATH_SCALE + (EXIT_SCALE - 1 - BREATH_SCALE) * x).toFloat(), (1 - x).toFloat())
            }
        }
    }

    // ── Scroll ──────────────────────────────────────────────────────────────────────────────

    // A move into a line takes longer the more room there is before it: MIN when the line follows
    // straight on, MAX after a pause. Rows at and above the line go at once; each row below waits
    // a little longer than the one above it, up to the fourth.
    private const val SCROLL_MIN_MS = 480
    private const val SCROLL_MAX_MS = 750
    private const val SCROLL_GAP_FROM_MS = 200L
    private const val SCROLL_GAP_TO_MS = 750L
    val SCROLL_EASE = CubicBezierEasing(0.4f, 0.1f, 0f, 1f)
    private val ROW_DELAYS_MS = intArrayOf(25, 44, 56, 63)

    /** How long the move into the next line takes, and how far ahead of it the scroll may start. */
    data class Scroll(val ms: Int, val leadMs: Long)

    /** The scroll for [room] ms between the end of one line and the start of the next. */
    fun scrollFor(room: Long): Scroll {
        val gap = room.coerceIn(SCROLL_GAP_FROM_MS, SCROLL_GAP_TO_MS)
        val share = (gap - SCROLL_GAP_FROM_MS).toDouble() / (SCROLL_GAP_TO_MS - SCROLL_GAP_FROM_MS)
        val ms = Math.round(SCROLL_MIN_MS + (SCROLL_MAX_MS - SCROLL_MIN_MS) * share).toInt()
        return Scroll(ms, minOf(ms.toLong(), room.coerceAtLeast(0L)))
    }

    /** How long row [below] rows under the line being scrolled to waits before it moves. */
    fun rowDelayMs(below: Int): Int = if (below <= 0) 0 else ROW_DELAYS_MS[minOf(below, ROW_DELAYS_MS.size) - 1]
}
