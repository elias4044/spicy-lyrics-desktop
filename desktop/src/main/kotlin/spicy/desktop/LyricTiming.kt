package spicy.desktop

import com.tx24.spicyplayer.lyrics.TimedLine
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType

private const val INTERLUDE_MS = 3_000L

internal fun lyricInterludes(lines: List<TimedLine>, type: LyricsType): List<TimedLine> {
    if (type == LyricsType.Static || lines.none { it.role == LineRole.LEAD && !it.untimed }) return lines
    val leads = lines.filter { it.role == LineRole.LEAD && !it.untimed }
    val cadence = leads.zipWithNext { a, b -> b.startMs - a.startMs }
        .filter { it in 650L..7_500L }.sorted().let { it.getOrNull(it.size / 2) ?: 3_800L }
    var leadIndex = 0
    var lastEnd = 0L
    var intervening = false
    return buildList {
        for ((index, original) in lines.withIndex()) {
            if (original.role != LineRole.LEAD || original.untimed) {
                intervening = intervening || original.untimed || original.role == LineRole.INTERLUDE
                if (original.role != LineRole.INTERLUDE) lastEnd = maxOf(lastEnd, original.endMs)
                add(original)
                continue
            }
            val firstWord = original.words.minOfOrNull { it.startMs } ?: original.startMs
            val gapEnd = minOf(firstWord, original.startMs)
            if (!intervening && gapEnd - lastEnd >= INTERLUDE_MS) {
                add(TimedLine(lastEnd, gapEnd, emptyList(), role = LineRole.INTERLUDE, oppositeAligned = original.oppositeAligned))
            }
            val next = leads.getOrNull(++leadIndex)
            val lineOnly = type == LyricsType.Line || original.lineTimed
            // LRC usually supplies onsets only. Infer a break only in an unusually long
            // interval ending exactly at the next onset. Explicit end times are authoritative.
            val nextStart = next?.startMs
            val inferred = lineOnly && nextStart != null && original.endMs == nextStart &&
                nextStart - original.startMs >= maxOf(8_500L, cadence * 2)
            val line = if (inferred) {
                val syllables = original.words.count { it.text.isNotBlank() }
                val singing = maxOf(cadence, (1_200L + 380L * syllables).coerceAtMost(7_000))
                val end = original.startMs + singing
                if (nextStart - end >= INTERLUDE_MS) original.copy(endMs = end) else original
            } else original
            val vocalEnd = if (lineOnly) line.endMs else line.words.maxOfOrNull { it.endMs } ?: line.endMs
            lastEnd = maxOf(lastEnd, vocalEnd)
            // A backing vocal that leads into this line is ordered under it in the document.
            val overlap = lines.subList(index + 1, lines.size).asSequence().takeWhile { it.role == LineRole.BACKGROUND }
                .minOfOrNull { it.startMs }
            if (overlap != null && lastOrNull()?.role == LineRole.INTERLUDE) {
                val dots = last()
                if (overlap < dots.endMs) {
                    removeAt(lastIndex)
                    if (overlap - dots.startMs >= INTERLUDE_MS) add(dots.copy(endMs = overlap))
                }
            }
            intervening = false
            add(line)
        }
    }
}

internal fun plainScrollProgress(position: Long, duration: Long): Double =
    if (duration <= 0) 0.0 else (position.toDouble() / duration).coerceIn(0.0, 1.0)

internal fun nextUpDue(position: Long, duration: Long): Boolean =
    duration > 0 && position >= 0 && duration - position in 0L..minOf(30_000L, duration / 5L)
