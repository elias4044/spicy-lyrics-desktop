package spicy.desktop

import com.tx24.spicyplayer.network.data.ProviderAttemptOutcome
import com.tx24.spicyplayer.network.data.RemoteLyricsQuality
import com.tx24.spicyplayer.network.data.RemoteLyricsResolution
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultButtonModel
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

internal fun RemoteLyricsQuality.displayLabel() = when (this) {
    RemoteLyricsQuality.WORD_SYNCED -> "Word synced"
    RemoteLyricsQuality.LINE_SYNCED -> "Line synced"
    RemoteLyricsQuality.PLAIN -> "Plain lyrics"
    RemoteLyricsQuality.NONE -> "No lyrics"
}

internal class LyricsSourcePicker(
    song: Track,
    sources: List<Pair<String, String>>,
    private val requested: String?,
    private val playing: String?,
    private val theme: () -> CoverTheme,
) : JPanel(BorderLayout(0, 10)) {
    private data class Row(val id: String?, val name: String)
    private val rows = listOf(Row(null, "Automatic"), Row("local", "Local lyrics")) + sources.map { Row(it.first, it.second) }
    private val results = mutableMapOf<String, RemoteLyricsResolution>()
    private val status = textLabel("", 12, color = MUTED)
    internal val primaryState = DefaultButtonModel()
    private val list = JList(rows.toTypedArray()).apply {
        fixedCellHeight = 54
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        addListSelectionListener { if (!it.valueIsAdjusting) updateState() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                val index = locationToIndex(event.point)
                if (event.clickCount == 2 && SwingUtilities.isLeftMouseButton(event) &&
                    index >= 0 && getCellBounds(index, index).contains(event.point)) {
                    SwingUtilities.getRootPane(this@apply)?.defaultButton?.doClick()
                }
            }
        })
    }
    val selectedId: String? get() = list.selectedValue?.id
    val selectedLyrics: RemoteLyricsResolution.Found? get() = results[selectedId] as? RemoteLyricsResolution.Found

    init {
        isOpaque = false
        val trackLabel = listOf(song.title, song.artist).filter(String::isNotBlank).joinToString(" · ")
        add(textLabel(trackLabel, 13, true).apply {
            preferredSize = Dimension(420, preferredSize.height)
            toolTipText = trackLabel
        }, BorderLayout.NORTH)
        list.cellRenderer = ListCellRenderer { _, row, _, selected, _ -> renderRow(row, selected) }
        list.selectedIndex = rows.indexOfFirst { it.id == requested }.coerceAtLeast(0)
        add(JScrollPane(list).apply {
            preferredSize = Dimension(460, (rows.size * 54 + 10).coerceIn(140, 320))
        }, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        updateState()
    }

    fun update(id: String, result: RemoteLyricsResolution) {
        results[id] = result
        list.repaint()
        updateState()
    }

    private fun updateState() {
        val row = list.selectedValue ?: return
        val result = results[row.id]
        primaryState.isEnabled = row.id == null || result is RemoteLyricsResolution.Found
        val checked = results.size
        val total = rows.size - 1
        status.text = when {
            row.id != null && result != null && result !is RemoteLyricsResolution.Found ->
                "${row.name}: ${availability(result)}"
            checked < total -> "Checking sources for this song · $checked of $total"
            else -> "${results.values.count { it is RemoteLyricsResolution.Found }} of $total sources have lyrics"
        }
    }

    private fun availability(result: RemoteLyricsResolution?): String {
        if (result == null) return "Checking…"
        if (result is RemoteLyricsResolution.Found) return result.selection.quality.displayLabel()
        return when (result.attempts.firstOrNull { it.outcome != ProviderAttemptOutcome.DISABLED }?.outcome) {
            ProviderAttemptOutcome.NEEDS_MATCH -> "Needs a track match"
            ProviderAttemptOutcome.COOLING_DOWN -> "Rate limited"
            ProviderAttemptOutcome.QUEUED -> "Try again later"
            ProviderAttemptOutcome.FILTERED -> "Filtered by settings"
            ProviderAttemptOutcome.UNAVAILABLE -> "Unavailable"
            else -> if (result is RemoteLyricsResolution.Unavailable) "Unavailable" else "No lyrics found"
        }
    }

    private fun renderRow(row: Row, selected: Boolean): Component {
        val result = results[row.id]
        val accent = theme().accent
        val detail = when {
            row.id == null -> "Choose the best match using your source preferences"
            row.id == playing -> "Currently playing"
            row.id == "local" -> "Imported, saved, or nearby lyric file"
            result is RemoteLyricsResolution.Found -> result.selection.payload.attribution?.originName
                ?.takeUnless { it.equals(row.name, ignoreCase = true) }
            else -> result?.attempts?.firstOrNull { it.sourceId == row.id }?.message
        }
        val surface = object : JPanel(BorderLayout(10, 0)) {
            override fun paintComponent(graphics: Graphics) {
                (graphics.create() as Graphics2D).useGraphics {
                    it.quality()
                    it.color = accent.alpha(if (selected) .22 else .065)
                    it.fill(rounded(1, 2, width - 2, height - 4, 12))
                    if (selected) {
                        it.color = accent.alpha(.66)
                        it.draw(rounded(1, 2, width - 2, height - 4, 12))
                    }
                }
            }
        }
        return surface.apply {
            isOpaque = false
            border = EmptyBorder(7, 11, 7, 10)
            add(JPanel(java.awt.GridLayout(2, 1)).apply {
                isOpaque = false
                add(textLabel(row.name, 13, true))
                add(textLabel(detail.orEmpty(), 11, color = MUTED).apply { toolTipText = detail })
            }, BorderLayout.CENTER)
            val badge = if (row.id == null) {
                if (requested == null) "Selected" else ""
            } else availability(result)
            if (badge.isNotEmpty()) add(JLabel(badge).apply {
                font = font(11, true)
                foreground = if (result is RemoteLyricsResolution.Found || row.id == null) accent else MUTED
                border = EmptyBorder(0, 3, 0, 3)
            }, BorderLayout.EAST)
        }
    }
}
