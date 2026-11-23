package spicy.desktop

import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.network.data.LyricsOrigins
import com.tx24.spicyplayer.network.data.RemoteLyricsQuality
import com.tx24.spicyplayer.network.data.RemoteLyricsResolution
import com.tx24.spicyplayer.network.data.SourceRanking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.coroutines.runBlocking

internal fun smokeCheck(directory: File) = runBlocking {
    check(directory.mkdirs() || directory.isDirectory)
    val settings = SettingsStore(File(directory, "settings.json"))
    val engine = LyricsEngine(directory, settings)
    val ids = engine.descriptors().map { it.id }.toSet()
    check(ids.size == 14)
    check(listOf("lrclib", "lrc_red", "rmm_revival", "genius", "youtube_transcript", "netease", "qq_music", "kuwo", "kugou").all(ids::contains)) {
        "Unexpected provider IDs: $ids"
    }
    val ranking = SourceRanking(engine.descriptors(), settings.value.policy)
    check(ranking.origins.any { it.id == LyricsOrigins.APPLE_MUSIC })
    check(ranking.asked("lrclib"))
    check(!ranking.asked("rmm_revival"))
    check(!ranking.asked("qq_music"))

    val song = Track("Smoke Test", "Test Artist", duration = 20_000)
    val raw = "[00:01.000]<00:01.000>First <00:01.450>word\n[00:04.000]<00:04.000>Another <00:04.650>line"
    val selection = checkNotNull(engine.asSelection(raw, "Local lyrics"))
    check(selection.quality == RemoteLyricsQuality.WORD_SYNCED)
    val timed = engine.render(selection, song, settings.value)
    check(timed.lyricsType == LyricsType.Syllable)
    check(timed.lines.flatMap { it.words }.any { it.text.trim() == "word" && it.startMs == 1450L })
    check(engine.asSelection("[00:01.000]Line one\n[00:04.000]Line two", "LRC")?.quality == RemoteLyricsQuality.LINE_SYNCED)
    check(engine.asSelection("plain words\nsecond row", "Plain")?.quality == RemoteLyricsQuality.PLAIN)
    check(engine.asSelection("<not-ttml />", "Invalid") == null)
    val request = song.lookup()
    val saved = engine.local.put(request.title, request.artist, song.title, song.artist, song.album, raw)
    val found = engine.resolve(song, settings.value, null) {} as RemoteLyricsResolution.Found
    check(found.selection.quality == RemoteLyricsQuality.WORD_SYNCED)
    check(engine.local.entries().single().key == saved.key)

    val lyricFolder = File(directory, "lyrics").apply { mkdirs() }
    atomicText(File(lyricFolder, "Other Artist - Other Song.lrc"), "[00:02.00]From a linked folder\n[00:06.00]Second row")
    settings.update { it.copy(lyricsFolder = lyricFolder.absolutePath, globalOffset = 125, clientKey = "sl_pk_example") }
    val folderResult = engine.resolve(Track("Other Song", "Other Artist", duration = 12_000), settings.value, null) {}
    check(folderResult is RemoteLyricsResolution.Found)
    check(folderResult.selection.quality == RemoteLyricsQuality.LINE_SYNCED)
    val backup = File(directory, "backup.json")
    settings.export(backup)
    check(!backup.readText().contains("sl_pk_example"))
    check(SettingsStore(File(directory, "settings.json")).value.globalOffset == 125)
    check(demoLyrics().lines.isNotEmpty())
    check(CoverTheme.from(null) == CoverTheme.Neutral)
    val cover = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until cover.height) {
        for (x in 0 until cover.width) cover.setRGB(x, y, Color(27, 171, 94).rgb)
    }
    val theme = CoverTheme.from(cover)
    check((theme.wells + theme.base + theme.accent).all { it.green > it.red && it.green > it.blue })
    val spans = arrayOf<BloomMotion.Span?>(
        BloomMotion.Span(0f, 50f, .25f, rowStart = true, rowEnd = false),
        BloomMotion.Span(50f, 50f, 1f, rowStart = false, rowEnd = true),
    )
    val edges = BloomMotion.sweepEdges(spans, longArrayOf(0, 700), longArrayOf(700, 1_400), 28f, 1050.0)
    check(edges[0] == edges[1] && edges[1] > 50)
    println("Verified 14 lyric providers, local formats, saved lyrics, settings backup, cover colors, and continuous Bloom timing.")
}
