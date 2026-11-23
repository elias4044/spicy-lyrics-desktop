package spicy.desktop

import com.tx24.spicyplayer.lyrics.LyricsState
import com.tx24.spicyplayer.lyrics.TimedLine
import com.tx24.spicyplayer.lyrics.TimedWord
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.lyrics.spicy.models.interludeDotTimes
import com.tx24.spicyplayer.lyrics.spicy.parser.GraphemeSegmenter
import com.tx24.spicyplayer.lyrics.spicy.parser.RtlDetector
import java.awt.Font
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.text.AttributedString
import kotlin.math.max

internal data class GlyphSlice(val shape: Shape, val left: Float, val width: Float, val rtl: Boolean)

internal class HeldWord(val start: Long, val duration: Long, val letterCount: Int)

internal data class LyricPiece(
    val word: TimedWord,
    val liftStart: Long,
    val glyphs: GlyphSlice,
    val letters: List<GlyphSlice>,
    val heldWord: HeldWord?,
    val letterOffset: Int,
    val span: BloomMotion.Span,
)

internal data class LyricRow(val layout: TextLayout, val x: Float, val baseline: Float, val pieces: List<LyricPiece>)
internal data class InterludeGlyph(val glyph: Shape, val advance: Float, val left: Float, val right: Float)

internal class LyricBox(
    val line: TimedLine,
    val top: Float,
    val height: Float,
    val size: Float,
    val right: Boolean,
    val rows: List<LyricRow>,
    val underRows: List<LyricRow>,
    val inkHeight: Float,
    val dot: InterludeGlyph? = null,
    val lineSynced: Boolean = false,
) {
    val spans = rows.flatMap { it.pieces }.map { it.span }.toTypedArray<BloomMotion.Span?>()
    val starts = rows.flatMap { it.pieces }.map { it.word.startMs }.toLongArray()
    val ends = rows.flatMap { it.pieces }.map { it.word.endMs }.toLongArray()
    val isBackground = line.role == LineRole.BACKGROUND
    val isInterlude = line.role == LineRole.INTERLUDE
    val dotTimes = if (dot != null) interludeDotTimes(line.startMs, line.endMs) else emptyList()
    val sungEnd = if (lineSynced || line.lineTimed) line.endMs else line.words.maxOfOrNull { it.endMs } ?: line.endMs
}

internal class LyricLayout(
    val width: Int,
    val mini: Boolean,
    val frc: FontRenderContext,
    val lines: List<LyricBox>,
    val lastTop: Float,
) {
    val anchors = lines.indices.filter { !lines[it].isBackground && !lines[it].line.untimed }
        .sortedBy { lines[it].line.startMs }
    val interludes = lines.indices.filter { lines[it].isInterlude }

    fun activeAt(time: Double): Int {
        val found = anchors.binarySearch { lines[it].line.startMs.toDouble().compareTo(time) }
        if (found >= 0) return found
        return -found - 2
    }
}

private data class WordRange(val word: TimedWord, val from: Int, val to: Int, val runStart: Long)
private data class ShapedWord(val word: TimedWord, val range: WordRange, val from: Int, val to: Int, val glyphs: GlyphSlice)
private val whitespace = Regex("\\s+")

internal fun layoutLyrics(frc: FontRenderContext, doc: LyricsState.Ready, width: Int, mini: Boolean, settings: Settings): LyricLayout {
    val base = when {
        mini -> (width * .068f).coerceIn(22f, 36f)
        doc.lyricsType == LyricsType.Static -> (width * .05f).coerceIn(12.8f, 40f)
        else -> (width * .07f).coerceIn(29.6f, 56f)
    } * settings.textScale / 100
    val hasDuet = doc.lines.any { it.role == LineRole.LEAD && it.oppositeAligned }
    val gap = if (settings.style == "Bloom") BloomMotion.GAP_EM else BloomMotion.SPRING_GAP_EM
    var top = 0f
    val display = if (settings.waitBubbles) lyricInterludes(doc.lines, doc.lyricsType)
        else doc.lines.filterNot { it.role == LineRole.INTERLUDE }
    val lines = display.mapIndexed { index, line ->
        val background = line.role == LineRole.BACKGROUND
        val timedWords = doc.lyricsType == LyricsType.Syllable && !line.lineTimed && !line.untimed
        val size = base * if (background) .75f else 1f
        val right = RtlDetector.isRtl(line.words.joinToString(" ") { it.text }) xor line.oppositeAligned
        val inset = width * if (mini) .022f else .05f
        val oppositeInset = if (hasDuet && !mini) width * .10f else 0f
        val left = inset + if (right) oppositeInset else 0f
        val available = (width - inset * 2 - oppositeInset).coerceAtLeast(10f)
        val face = when {
            background -> LyricFonts.Face.Semibold
            doc.lyricsType == LyricsType.Static -> LyricFonts.Face.Medium
            else -> LyricFonts.Face.Bold
        }
        val primary = LyricFonts.face(settings, face, size)
        val bloom = settings.style == "Bloom"
        val dot = if (line.role == LineRole.INTERLUDE) {
            val glyph = TextLayout("•", primary.deriveFont(size * 1.3f), frc)
            InterludeGlyph(glyph.getOutline(null), glyph.advance.coerceAtLeast(1f), left, left + available)
        } else null
        val rows = if (dot != null) emptyList()
            else makeRows(line.words, size, primary, !background, frc, left, available, right, size, timedWords, bloom)
        var end = rows.lastOrNull()?.let { it.baseline + size * .22f } ?: size * .845f
        val underSize = (size * .49f).coerceAtLeast(12f)
        val underFont = LyricFonts.face(settings, LyricFonts.Face.Medium, underSize)
        val under = mutableListOf<LyricRow>()
        if (settings.romanization && line.words.any { !it.romanized.isNullOrBlank() }) {
            val words = line.words.map { it.copy(text = it.romanized ?: it.text) }
            under += makeRows(words, underSize, underFont, false, frc, left, available, right, end + underSize, timedWords, bloom)
            end = under.lastOrNull()?.let { it.baseline + underSize * .24f } ?: end
        }
        val translated = line.translation
        if (settings.translation && !translated.isNullOrBlank()) {
            val translation = TimedWord(translated, line.startMs, line.endMs, false)
            under += makeRows(listOf(translation), underSize, underFont, false, frc, left, available, right, end + underSize, false, bloom)
            end = under.lastOrNull()?.let { it.baseline + underSize * .24f } ?: end
        }
        val next = display.getOrNull(index + 1)
        val spacing = when {
            next?.role == LineRole.BACKGROUND && next.groupId == line.groupId -> base * .23f
            line.role == LineRole.INTERLUDE -> base * .48f
            doc.lyricsType == LyricsType.Static -> base * .47f
            else -> base * gap
        }
        val height = end + spacing
        LyricBox(line, top, height, size, right, rows, under, end, dot, doc.lyricsType == LyricsType.Line).also { top += height }
    }
    return LyricLayout(width, mini, frc, lines, lines.lastOrNull()?.top ?: 0f)
}

private fun makeRows(
    words: List<TimedWord>, size: Float, primary: Font, bold: Boolean, frc: FontRenderContext,
    left: Float, width: Float, right: Boolean, firstBaseline: Float, timed: Boolean, bloom: Boolean,
): List<LyricRow> {
    val ranges = mutableListOf<WordRange>()
    val text = buildString {
        var runStart = 0L
        for (word in words) {
            val value = word.text.replace(whitespace, " ").trim()
            if (value.isEmpty()) continue
            if (isEmpty() || !word.attached) runStart = word.startMs
            if (isNotEmpty() && !word.attached) append(' ')
            val from = length
            append(value)
            ranges += WordRange(word, from, length, runStart)
        }
    }
    if (text.isEmpty()) return emptyList()

    val fallback = Font("Dialog", if (bold) Font.BOLD else Font.PLAIN, size.toInt()).deriveFont(size)
    val attributed = AttributedString(text).apply {
        addAttribute(TextAttribute.FONT, primary)
        addAttribute(TextAttribute.KERNING, TextAttribute.KERNING_ON)
        var offset = 0
        for (grapheme in GraphemeSegmenter.joinConjuncts(GraphemeSegmenter.fallback(text))) {
            if (primary.canDisplayUpTo(grapheme) >= 0) addAttribute(TextAttribute.FONT, fallback, offset, offset + grapheme.length)
            offset += grapheme.length
        }
    }
    val measurer = LineBreakMeasurer(attributed.iterator, frc)
    val rows = mutableListOf<LyricRow>()
    var stripX = 0f
    while (measurer.position < text.length) {
        val start = measurer.position
        val layout = measurer.nextLayout(width)
        val end = measurer.position
        val x = if (right) left + width - layout.visibleAdvance else left
        val baseline = firstBaseline + rows.size * size * 1.1818182f
        val pieces = if (!timed) emptyList() else run {
            val outline = layout.getOutline(null)
            val parts = ranges.filter { it.from < end && it.to > start }.map { range ->
                val from = maxOf(range.from, start)
                val to = minOf(range.to, end)
                val glyphs = sliceGlyphs(layout, outline, from - start, to - start, end - start, size)
                ShapedWord(sliceTiming(range, from, to), range, from, to, glyphs)
            }
            val output = mutableListOf<LyricPiece>()
            var i = 0
            while (i < parts.size) {
                val head = parts[i]
                var afterRun = i + 1
                while (afterRun < parts.size && parts[afterRun].word.attached) afterRun++
                val run = parts.subList(i, afterRun)
                val lifetime = run.maxOf { it.word.endMs } - head.range.runStart
                val graphemes = run.map { GraphemeSegmenter.fallback(text.substring(it.from, it.to)) }
                val count = graphemes.sumOf { it.size }
                val latin = run.all { part ->
                    !part.glyphs.rtl && text.substring(part.from, part.to).codePoints().allMatch { cp ->
                        !Character.isLetter(cp) || Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN
                    }
                }
                val letterGroups = if (lifetime >= 1_000 && (if (bloom) count in 2..7 else count in 1..36) &&
                    latin && head.from == head.range.from) {
                    run.mapIndexed { index, part ->
                        var offset = part.from - start
                        graphemes[index].map {
                            val slice = sliceGlyphs(layout, outline, offset, offset + it.length, end - start, size)
                            offset += it.length
                            slice
                        }
                    }
                } else emptyList()
                val heldWord = if (letterGroups.isEmpty()) null else {
                    HeldWord(head.range.runStart, lifetime, count)
                }
                var letterOffset = 0
                for ((groupIndex, part) in run.withIndex()) {
                    val index = i + groupIndex
                    val rowEnd = index == parts.lastIndex
                    val next = parts.getOrNull(index + 1)
                    val glyphs = part.glyphs
                    val spacing = when {
                        next == null || next.word.attached -> 0f
                        next.glyphs.rtl != glyphs.rtl -> size * BloomMotion.DEFAULT_GAP_EM
                        glyphs.rtl -> (glyphs.left - next.glyphs.left - next.glyphs.width).coerceAtLeast(0f)
                        else -> (next.glyphs.left - glyphs.left - glyphs.width).coerceAtLeast(0f)
                    }
                    val letters = letterGroups.getOrNull(groupIndex).orEmpty()
                    val span = BloomMotion.Span(stripX, glyphs.width + spacing,
                        BloomMotion.reach(count, heldWord != null, rowEnd), index == 0, rowEnd)
                    stripX += span.w
                    output += LyricPiece(part.word, part.range.runStart, glyphs, letters, heldWord, letterOffset, span)
                    letterOffset += letters.size
                }
                i = afterRun
            }
            output
        }
        rows += LyricRow(layout, x, baseline, pieces)
    }
    return rows
}

private fun sliceTiming(range: WordRange, from: Int, to: Int): TimedWord {
    if (from == range.from && to == range.to) return range.word
    val word = range.word
    val duration = (word.endMs - word.startMs).toDouble() / (range.to - range.from)
    return word.copy(startMs = word.startMs + ((from - range.from) * duration).toLong(),
        endMs = word.startMs + ((to - range.from) * duration).toLong())
}

private fun sliceGlyphs(layout: TextLayout, outline: Shape, from: Int, to: Int, total: Int, size: Float): GlyphSlice {
    val display = layout.getLogicalHighlightShape(from, to)
    val bounds = display.bounds2D
    val area = Area(display)
    val pad = size * .16
    if (bounds.minX <= .1) area.add(Area(Rectangle2D.Double(-pad, bounds.y, pad + .1, bounds.height)))
    if (bounds.maxX >= layout.advance - .1 || to == total && layout.isLeftToRight) {
        area.add(Area(Rectangle2D.Double(bounds.maxX - .1, bounds.y, pad + .1, bounds.height)))
    }
    area.intersect(Area(outline))
    return GlyphSlice(area, bounds.x.toFloat(), max(.25f, bounds.width.toFloat()), layout.getCharacterLevel(from).toInt() and 1 != 0)
}
