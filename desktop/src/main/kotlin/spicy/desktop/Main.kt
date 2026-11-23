package spicy.desktop

import com.tx24.spicyplayer.lyrics.LyricsState
import com.tx24.spicyplayer.lyrics.TimedLine
import com.tx24.spicyplayer.lyrics.TimedWord
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.providers.ModelSyncResult
import com.tx24.spicyplayer.translation.*
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.roundToInt

val WINDOW = Color.BLACK
val PANEL = Color(21, 21, 21)
val EDGE = Color(44, 44, 44)
val TEXT = Color.WHITE
val MUTED = Color(174, 174, 174)
val ACCENT = Color.WHITE
const val APP_TITLE = "Spicy Lyrics Desktop"
const val APP_AUTHOR = "elias4044"

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--smoke-check") {
        smokeCheck(File(args[1]))
        return
    }
    System.setProperty("sun.java2d.d3d", "true")
    installLook()
    if (args.firstOrNull() == "--render-check") {
        SwingUtilities.invokeAndWait {
            val mode = args.getOrNull(5).orEmpty()
            val clock = args.getOrNull(2)?.toLongOrNull() ?: 20_250L
            val width = args.getOrNull(3)?.toIntOrNull() ?: 1180
            val height = args.getOrNull(4)?.toIntOrNull() ?: 755
            val data = java.nio.file.Files.createTempDirectory("spicy-render").toFile()
            try {
                DesktopApp(data, File(data, "media-bridge.exe"), emptyList())
                    .renderPreview(File(args[1]), clock, Dimension(width, height), mode)
            } finally {
                data.deleteOnExit()
            }
        }
        return
    }
    SwingUtilities.invokeLater {
        val data = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Spicy Lyrics for Windows")
        val configuredHelper = System.getProperty("spicy.bridge") ?: System.getenv("SPICY_MEDIA_BRIDGE")
        val bundledHelper = File(System.getProperty("jpackage.app-path", "")).absoluteFile.parentFile?.resolve("app/media-bridge.exe")
        val helper = configuredHelper?.let(::File) ?: bundledHelper?.takeIf { it.isFile }
            ?: listOf(File("desktop/build/native/media-bridge.exe"), File("build/native/media-bridge.exe")).firstOrNull { it.isFile }
            ?: File("media-bridge.exe")
        DesktopApp(data, helper, args.filterNot { it.startsWith("--") }.map(::File).filter { it.isFile }).show()
    }
}

internal inline fun Graphics2D.useGraphics(block: (Graphics2D) -> Unit) {
    try { block(this) } finally { dispose() }
}

fun font(size: Int, bold: Boolean = false) = Font("Segoe UI", if (bold) Font.BOLD else Font.PLAIN, size)
fun textLabel(value: String = "", size: Int = 13, bold: Boolean = false, color: Color = TEXT) = JLabel(value).apply {
    font = font(size, bold)
    foreground = color
}
fun box(axis: Int, color: Color = WINDOW) = JPanel().apply { layout = BoxLayout(this, axis); background = color }

private fun installLook() {
    runCatching { UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel") }
    mapOf("control" to PANEL, "nimbusBase" to EDGE, "nimbusBlueGrey" to EDGE,
        "nimbusLightBackground" to PANEL, "text" to TEXT, "nimbusSelectionBackground" to Color(68, 68, 68),
        "nimbusSelectedText" to TEXT, "nimbusFocus" to ACCENT, "info" to PANEL).forEach { (key, value) -> UIManager.put(key, value) }
    UIManager.put("defaultFont", font(13))
    UIManager.put("OptionPane.background", WINDOW)
    UIManager.put("Panel.background", WINDOW)
    UIManager.put("Label.foreground", TEXT)
}

class DesktopApp(data: File, helper: File, private val launchFiles: List<File>) {
    val frame = JFrame(APP_TITLE).apply { isUndecorated = true }
    val settings = SettingsStore(File(data, "settings.json"))
    val engine = LyricsEngine(data, settings)
    private val translationEngine = TranslationEngine(SongLanguageDetector { null }, DiskTranslationCache(File(data, "translations")))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stage = Stage(::lyricPosition, ::jumpToLyric, ::toggleCover)
    private val playback = Playback(helper, ::onMedia)
    private val browser = BrowserLink(data, ::onMedia)
    private var mode = ""
    private var media = MediaState()
    private var manualTrack: Track? = null
    private var manualPosition = 0L
    private var manualAt = nowMs()
    private var manualPlaying = false
    private var lastTrack: Track? = null
    private var ready: LyricsState.Ready? = null
    private var temporary: String? = null
    private var selectedProvider: String? = null
    private var appliedProvider: String? = null
    private var displayedSelection: RemoteLyricsSelection? = null
    private var sourcePicker: LyricsSourcePicker? = null
    private var generation = 0
    private var lookupJob: Job? = null
    var attempts: List<ProviderAttempt> = emptyList()
        private set
    val track: Track? get() = if (mode in setOf("manual", "demo")) manualTrack else media.track
    private var mini = false
    private var maximizedBounds: Rectangle? = null
    private var fullScreenDevice: GraphicsDevice? = null
    private var previousBounds: Rectangle? = null
    private var restoreMaximized = false
    private var sliderUpdate = false
    private var dragging = false
    private val scene = ArtworkScene()
    internal val coverTheme get() = scene.theme
    private val chrome = WindowChrome(frame, { coverTheme }, canMaximize = { !mini })
    private val sheets = DialogSheets { coverTheme }
    private val dialogOwner get() = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow ?: frame
    private val root = ArtworkRoot(scene, { stage.settings }, { stage.playing })
    private val heading = textLabel(APP_TITLE, 16, true)
    private val author = textLabel(APP_AUTHOR, 12, false, MUTED)
    private val status = textLabel("Windows media", 12, false, MUTED)
    private val homeTitle = textLabel("Play a song", 33, true)
    private val homeDetail = textLabel("", 14, false, MUTED)
    private val connection = action("Connect", "sources", "Playback sources") { showConnections() }
    private val options = action("Settings", "settings") { openSettings() }
    private val compact = action("", "mini", "Mini player (Ctrl+M)") { toggleMini() }
    private val expand = action("", "fullscreen", "Fullscreen (F11)") { fullscreen() }
    private val sourceTag = textLabel("", 12, false, MUTED)
    private val timeline = PlaybackSlider({ coverTheme })
    private val elapsed = textLabel("0:00", 12, true, MUTED)
    private val total = textLabel("–:––", 12, false, MUTED).apply { horizontalAlignment = SwingConstants.RIGHT }
    private val backward = PlayerButton("", "rewind", { coverTheme }, size = 42, name = "Previous track", iconSize = 25.0) { control("previous") }
    private val forward = PlayerButton("", "advance", { coverTheme }, size = 42, name = "Next track", iconSize = 25.0) { control("next") }
    private val play = PlayerButton("", "play", { coverTheme }, size = 50, name = "Play (Space)", iconSize = 38.0) { control("toggle") }
    private val sync = action("Sync", "sync", "Change lyrics timing") { editTiming() }
    private val import = action("Lyrics", "lyrics", "Import, choose source, or manage lyrics") { showLyricsMenu() }
    private val sliderTimer = Timer(55) { transport() }
    private val mediaButtonRow = strip(FlowLayout.CENTER, 6).apply { add(backward); add(play); add(forward) }
    private val rightTools = strip(FlowLayout.RIGHT, 5).apply { add(sync); add(import) }
    private val footerSource = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(JPanel(java.awt.GridLayout(2, 1)).apply {
            isOpaque = false
            add(status)
            add(sourceTag)
        }, BorderLayout.CENTER)
    }
    private val footerFrame = JPanel(BorderLayout()).apply { isOpaque = false }
    private lateinit var headerBar: JPanel
    private lateinit var headerActions: JPanel
    private lateinit var footerDock: JPanel
    private lateinit var footerActions: JPanel
    private lateinit var progressRow: JPanel
    private lateinit var focusTools: JPanel
    private lateinit var focusTransport: JPanel
    private lateinit var focusPanel: JPanel
    private var settingsWindow: SettingsDialog? = null
    private var preview = false

    private fun action(label: String, symbol: String, name: String = label, block: () -> Unit) =
        PlayerButton(label, symbol, { coverTheme }, name = name, action = block)

    private fun strip(alignment: Int, gap: Int) = JPanel(FlowLayout(alignment, gap, 0)).apply { isOpaque = false }

    fun show() {
        setup()
        frame.isVisible = true
        sliderTimer.start()
        if (!settings.value.welcomeSeen) showWelcome()
        if (frame.isDisplayable && launchFiles.isNotEmpty()) openFiles(launchFiles)
    }

    private fun showWelcome() {
        val content = JPanel(BorderLayout(0, 14)).apply {
            add(playerMark(68) { coverTheme }, BorderLayout.NORTH)
            add(JTextArea(
                "Play music in a Windows media app and Spicy Lyrics will follow it automatically. " +
                    "You can also open a local track or start the demo to see synced lyrics in action.",
                0, 40,
            ).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }, BorderLayout.CENTER)
        }
        val choice = sheets.choose(frame, "Welcome to Spicy Lyrics", content, "Continue", "Try the demo", "Open audio")
        if (!frame.isDisplayable) return
        change { it.copy(welcomeSeen = true) }
        when (choice) {
            1 -> startManual(demoTrack, true)
            2 -> chooseFiles()
        }
    }

    private fun setup() {
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.minimumSize = Dimension(430, 350)
        frame.size = Dimension(1180, 790)
        frame.setLocationRelativeTo(null)
        frame.isAlwaysOnTop = settings.value.alwaysOnTop
        frame.iconImage = appIcon(64)
        stage.scene = scene
        stage.repaintScene = { root.repaint() }
        root.themeChanged = { settingsWindow?.refreshTheme() }
        val header = object : JPanel(BorderLayout(10, 0)) {
            override fun paintComponent(graphics: Graphics) {
                val g = graphics.create() as Graphics2D
                g.useGraphics {
                    it.paint = GradientPaint(0f, 0f, Color.BLACK.alpha(.27), 0f, height.toFloat(), Color.BLACK.alpha(.04))
                    it.fillRect(0, 0, width, height)
                }
            }
        }.apply {
            isOpaque = false
            border = EmptyBorder(11, 22, 10, 12)
        }
        val mark = playerMark(36) { coverTheme }
        val brandLines = JPanel(BorderLayout(0, 1)).apply {
            isOpaque = false
            border = EmptyBorder(0, 0, 0, 3)
            add(heading, BorderLayout.CENTER)
            add(author, BorderLayout.SOUTH)
        }
        val brand = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            add(mark, BorderLayout.WEST)
            add(brandLines, BorderLayout.CENTER)
        }
        headerBar = header
        headerActions = strip(FlowLayout.RIGHT, 4)
        installHeaderActions()
        chrome.dragFrom(header, brand, brandLines, heading, author, mark, headerActions)
        header.add(brand, BorderLayout.CENTER)
        header.add(headerActions, BorderLayout.EAST)
        stage.setHome(home())
        root.add(header, BorderLayout.NORTH)
        root.add(stage, BorderLayout.CENTER)
        root.add(footer(), BorderLayout.SOUTH)
        installFocusControls()
        frame.contentPane = root
        root.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = responsiveChrome()
        })
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) = close()
        })
        installKeys()
        setDropTarget()
        applyAppearance()
        chrome.refresh()
        transport()
        responsiveChrome()
    }

    private fun close() {
        sliderTimer.stop()
        scope.cancel()
        playback.close()
        browser.close()
    }

    internal fun renderPreview(file: File, clock: Long, size: Dimension, kind: String) {
        preview = true
        try {
            setup()
            if (kind != "idle") startManual(demoTrack, true)
            if (kind in setOf("presentation", "next")) maximizedBounds = Rectangle()
            if (kind == "mono") stage.artwork = null
            if (kind == "mini") {
                mini = true
                stage.mini = true
            }
            stage.settings = stage.settings.copy(style = if (kind == "bloom") "Bloom" else "Spring")
            val phase = if (kind.startsWith("motion-")) kind.substringAfter("motion-").toDoubleOrNull() ?: 0.0 else 0.0
            scene.settlePalette(phase)
            manualPlaying = false
            stage.playing = false
            manualPosition = clock
            if (kind == "plain" || kind == "line") {
                val type = if (kind == "plain") LyricsType.Static else LyricsType.Line
                showReady(demoLyrics().copy(lyricsType = type))
            }
            stage.resetScroll()
            transport()
            root.size = size
            responsiveChrome()
            fun layout(container: Container) {
                container.doLayout()
                container.components.filterIsInstance<Container>().forEach(::layout)
            }
            layout(root)
            val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().useGraphics { graphics ->
                root.paint(graphics)
                if (kind == "settings") {
                    val settingsDialog = SettingsDialog(this)
                    try {
                        val content = settingsDialog.content()
                        content.size = size
                        layout(content)
                        content.paint(graphics)
                    } finally {
                        settingsDialog.dispose()
                    }
                }
            }
            check(ImageIO.write(image, "png", file))
        } finally {
            frame.dispose()
            close()
        }
    }

    private fun home() = box(BoxLayout.Y_AXIS).apply {
        isOpaque = false
        val mark = playerMark(80) { coverTheme }.apply {
            minimumSize = Dimension(80, 80)
            maximumSize = Dimension(80, 80)
            alignmentX = Component.CENTER_ALIGNMENT
        }
        homeTitle.alignmentX = Component.CENTER_ALIGNMENT
        homeDetail.alignmentX = Component.CENTER_ALIGNMENT
        val actions = strip(FlowLayout.CENTER, 9).apply {
            add(PlayerButton("Open audio", "folder", { coverTheme }, prominent = true, size = 41) { chooseFiles() })
            add(PlayerButton("Try the demo", "play", { coverTheme }, size = 41) { startManual(demoTrack, true) })
            alignmentX = Component.CENTER_ALIGNMENT
        }
        add(mark)
        add(Box.createVerticalStrut(19))
        add(homeTitle)
        add(Box.createVerticalStrut(8))
        add(homeDetail)
        add(Box.createVerticalStrut(22))
        add(actions)
    }

    private fun footer(): JPanel {
        timeline.addChangeListener {
            if (sliderUpdate) return@addChangeListener
            if (timeline.valueIsAdjusting) dragging = true
            else {
                dragging = false
                seekTo((duration() * timeline.value / 1000.0).toLong())
            }
        }
        footerDock = JPanel(BorderLayout(0, 4)).apply { isOpaque = false }
        progressRow = JPanel(BorderLayout(7, 0)).apply {
            isOpaque = false
            elapsed.preferredSize = Dimension(32, 20)
            total.preferredSize = Dimension(32, 20)
            add(elapsed, BorderLayout.WEST)
            add(timeline, BorderLayout.CENTER)
            add(total, BorderLayout.EAST)
        }
        footerActions = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(footerSource, BorderLayout.WEST)
            add(mediaButtonRow, BorderLayout.CENTER)
            add(rightTools, BorderLayout.EAST)
        }
        footerDock.add(progressRow, BorderLayout.NORTH)
        footerDock.add(footerActions, BorderLayout.CENTER)
        return footerFrame.apply {
            border = EmptyBorder(1, 25, 17, 25)
            add(footerDock)
        }
    }

    private fun installHeaderActions() {
        headerActions.removeAll()
        for (button in listOf(connection, compact, expand, options)) headerActions.add(button)
        headerActions.add(Box.createHorizontalStrut(4))
        headerActions.add(chrome.buttons)
    }

    private fun installFocusControls() {
        focusTools = strip(FlowLayout.CENTER, 3)
        val capsule = SoftPanel(lifted = true).apply {
            border = EmptyBorder(4, 5, 4, 5)
            add(focusTools)
        }
        focusTransport = JPanel(BorderLayout()).apply { isOpaque = false }
        focusPanel = object : JPanel(null) {
            init { isOpaque = false }
            override fun contains(x: Int, y: Int) = components.any { it.bounds.contains(x, y) }
            override fun doLayout() {
                if (!stage.presentation) return
                val coverHeight = (height - 36).coerceAtLeast(0)
                val topWidth = minOf(width, focusTools.preferredSize.width + 11)
                capsule.setBounds((width - topWidth) / 2, 13, topWidth, 44)
                focusTransport.setBounds(4, (coverHeight - 63).coerceAtLeast(56), (width - 8).coerceAtLeast(1), 54)
                progressRow.setBounds(3, coverHeight + 7, (width - 6).coerceAtLeast(1), 24)
            }
            override fun paintComponent(graphics: Graphics) {
                val cut = (height - 36).coerceAtLeast(0)
                if (cut <= 0) return
                (graphics.create() as Graphics2D).useGraphics { g ->
                    g.quality()
                    g.clip(rounded(0, 0, width, cut, 22))
                    g.paint = GradientPaint(0f, cut * .58f, Color.BLACK.alpha(0.0), 0f, cut.toFloat(), Color.BLACK.alpha(.56))
                    g.fillRect(0, 0, width, cut)
                }
            }
        }.apply {
            add(capsule)
            add(focusTransport)
            isVisible = false
        }
        stage.presentationControls = focusPanel
        stage.add(focusPanel)
    }

    private fun refreshPresentation() {
        if (!::focusPanel.isInitialized) return
        val next = maximizedBounds != null && !mini && track != null &&
            stage.settings.fullscreenStage && !stage.settings.fullCover
        if (stage.presentation == next) return
        stage.presentation = next
        focusPanel.isVisible = next
        headerBar.isVisible = !next
        if (next) {
            for (button in listOf(connection, sync, import, options, expand)) focusTools.add(button)
            focusTransport.add(mediaButtonRow, BorderLayout.CENTER)
            focusPanel.add(progressRow)
        } else {
            installHeaderActions()
            rightTools.add(sync)
            rightTools.add(import)
            footerActions.add(mediaButtonRow, BorderLayout.CENTER)
            footerDock.add(progressRow, BorderLayout.NORTH)
        }
        responsiveChrome()
        root.revalidate()
        stage.resetScroll()
    }

    private fun responsiveChrome() {
        val wide = root.width >= 690 && !mini && !stage.presentation
        options.text = if (wide) "Settings" else ""
        connection.text = if (root.width >= 520 && !stage.presentation) "Connect" else ""
        sync.text = if (wide) "Sync" else ""
        import.text = if (wide) "Lyrics" else ""
        expand.isVisible = wide || stage.presentation
        val fullscreenSymbol = if (maximizedBounds != null) "close" else "fullscreen"
        if (expand.symbol != fullscreenSymbol) {
            expand.symbol = fullscreenSymbol
            expand.toolTipText = if (maximizedBounds != null) "Exit fullscreen (Esc)" else "Fullscreen (F11)"
            expand.accessibleContext.accessibleName = expand.toolTipText
        }
        status.isVisible = wide
        sourceTag.isVisible = wide
        val toolsWidth = sync.preferredSize.width + import.preferredSize.width + 15
        val side = if (wide) maxOf(toolsWidth, if (root.width >= 870) 218 else 174) else toolsWidth
        val size = Dimension(side, 50)
        if (footerSource.preferredSize != size) footerSource.preferredSize = size
        if (rightTools.preferredSize != size) rightTools.preferredSize = size
        footerFrame.border = if (mini) EmptyBorder(1, 12, 10, 12) else EmptyBorder(1, 25, 17, 25)
    }

    private fun installKeys() {
        fun bind(key: String, name: String, execute: () -> Unit) {
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name)
            root.actionMap.put(name, object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) = execute() })
        }
        bind("SPACE", "play") { control("toggle") }
        bind("LEFT", "rewind") { seekTo((position() - 5000).coerceAtLeast(0)) }
        bind("RIGHT", "advance") { seekTo(position() + 5000) }
        bind("F11", "fullscreen") { fullscreen() }
        bind("ESCAPE", "leave-fullscreen") { if (maximizedBounds != null) fullscreen() }
        bind("control O", "open") { chooseFiles() }
        bind("control M", "mini") { toggleMini() }
        bind("control L", "cover-view") { toggleCover() }
        bind("control comma", "settings") { openSettings() }
    }

    private fun setDropTarget() {
        root.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val files = (support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>()
                if (files.isEmpty()) return false
                openFiles(files)
                return true
            }
        }
    }

    private fun onMedia(next: MediaState) {
        if (mode in setOf("demo", "manual") || mode != "local" && next.session == "local") return
        if ((mode == "browser") != (next.session == "browser")) return
        if (mode == "local" && next.session !in setOf("local", "") && next.error == null) return
        media = next
        val active = next.track
        status.text = when {
            mode == "local" -> "Local audio"
            mode == "browser" -> "Browser companion"
            next.session.isBlank() -> "Windows media"
            else -> MediaSession(next.session, next.playing).name
        }
        val newArt = stage.artwork !== next.art
        stage.artwork = next.art
        stage.playing = next.playing
        stage.track = active
        if (active == null) {
            stage.status = if (next.error != null) "Windows media unavailable" else "Play something to get started"
            stage.detail = next.error ?: "Start music in another app or choose a playback source."
        }
        handleTrack(active)
        if (active == null || newArt) stage.wake()
    }

    private fun handleTrack(current: Track?) {
        val before = lastTrack
        val changed = before?.key != current?.key || before?.file != current?.file
        val improved = before != null && current != null && (before.duration <= 0 && current.duration > 0 ||
            before.album.isBlank() && current.album.isNotBlank())
        lastTrack = current
        if (changed) {
            sourcePicker?.let { SwingUtilities.getWindowAncestor(it)?.dispose() }
            temporary = null
            selectedProvider = null
            appliedProvider = null
            attempts = emptyList()
            displayedSelection = null
            ready = null
            stage.lyrics = null
            stage.resetScroll()
            lookup()
        } else if (improved && ready == null) lookup()
    }

    fun lookup(refresh: Boolean = false, prefetched: RemoteLyricsResolution.Found? = null) {
        val epoch = ++generation
        lookupJob?.cancel()
        val current = track ?: run { sourceTag.text = ""; stage.loading = false; return }
        if (mode == "demo") {
            stage.loading = false
            showReady(demoLyrics(current == demoEncore))
            return
        }
        val config = settings.value
        val override = temporary
        val only = selectedProvider
        val previous = appliedProvider
        stage.status = "Looking for lyrics…"
        stage.detail = if (only == null) "Checking enabled sources" else "Checking the selected source"
        stage.loading = true
        stage.wake()
        lookupJob = scope.launch {
            try {
                var shown: RemoteLyricsSelection? = null
                suspend fun apply(resolution: RemoteLyricsResolution) {
                    val found = resolution as? RemoteLyricsResolution.Found
                    if (found != null && shown != found.selection) {
                        shown = found.selection
                        val document = engine.render(found.selection, current, config)
                        onUi {
                            if (generation == epoch) {
                                if (displayedSelection?.source?.id != found.selection.source.id) stage.resetScroll()
                                displayedSelection = found.selection
                                appliedProvider = only
                                showReady(document)
                            }
                        }
                    }
                    onUi { if (generation == epoch) attempts = resolution.attempts }
                }
                val result = prefetched ?: withContext(Dispatchers.IO) {
                    engine.resolve(current, config, override, only, refresh, ::apply)
                }
                apply(result)
                val final = (result as? RemoteLyricsResolution.Found)?.selection
                if (final == null) onUi {
                    if (generation != epoch) return@onUi
                    stage.loading = false
                    if (only != null && ready != null) {
                        selectedProvider = previous
                        toast("The selected source has no usable lyrics for this song.")
                        return@onUi
                    }
                    stage.lyrics = null
                    ready = null
                    displayedSelection = null
                    appliedProvider = null
                    sourceTag.text = ""
                    stage.status = if (result is RemoteLyricsResolution.Unavailable) "Lyrics sources unavailable" else "No lyrics found"
                    stage.detail = if (result is RemoteLyricsResolution.Unavailable) "See the last lookup under Settings." else "Try another source or import a lyric file."
                    stage.wake()
                }
                if (final != null && config.translation && config.translationConsent) {
                    val original = engine.render(final, current, config.copy(humanRomanization = false))
                    val translated = withContext(Dispatchers.IO) { translate(current, original, config) }
                    if (translated != null) onUi {
                        if (generation == epoch) {
                            val baseline = ready ?: original
                            showReady(baseline.copy(lines = baseline.lines.mapIndexed { index, line ->
                                line.copy(translation = translated.getOrNull(index) ?: line.translation)
                            }))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onUi {
                    if (generation != epoch) return@onUi
                    stage.loading = false
                    if (ready == null) {
                        stage.status = "Lyrics lookup failed"
                        stage.detail = error.message ?: "Try searching again."
                    } else {
                        if (only != null && displayedSelection?.source?.id != only) selectedProvider = previous
                        toast(error.message ?: "The lyrics request failed.")
                    }
                    stage.wake()
                }
            }
        }
    }

    private suspend fun translate(song: Track, lyrics: LyricsState.Ready, config: Settings): List<String?>? {
        val provider = TranslationProvider.stored(config.translator)
        val translator = when (provider) {
            TranslationProvider.DeepL -> {
                if (config.translationKey.isBlank()) return null
                DeepLTranslator(engine.client, config.translationKey)
            }
            TranslationProvider.OpenAi -> {
                if (config.translationEndpoint.isBlank() || config.translationModel.isBlank()) return null
                OpenAiTranslator(engine.client, config.translationEndpoint, config.translationKey, config.translationModel)
            }
            TranslationProvider.Gemini -> {
                if (config.translationKey.isBlank()) return null
                GeminiTranslator(engine.client, config.translationKey, config.translationModel.ifBlank { GeminiModels.DEFAULT })
            }
            else -> GoogleTranslator(engine.client)
        }
        val document = TranslationDocument.from(song.key, lyrics.source.orEmpty(), lyrics.lines, lyrics.translationLanguage)
        val preferences = TranslationPreferences(config.translationLanguage, provider = provider, humanTranslations = config.humanTranslation,
            aiVariant = (config.translationEndpoint + " " + config.translationModel).trim())
        return when (val result = translationEngine.translate(document, preferences, null, translator, human = {
            if (config.humanTranslation) engine.humanTranslation(song, config.translationLanguage) else null
        })) {
            is TranslationOutcome.Translated -> result.result.texts
            else -> null
        }
    }

    private fun showReady(value: LyricsState.Ready) {
        ready = value
        stage.loading = false
        stage.lyrics = value
        val type = when (value.lyricsType) {
            LyricsType.Syllable -> "Word synced"
            LyricsType.Line -> "Line synced"
            else -> "Plain lyrics"
        }
        sourceTag.text = if (!settings.value.credits) "" else "${value.source ?: value.provider}   ·   $type"
        sourceTag.toolTipText = listOfNotNull(value.source, value.provider, value.maker?.username, value.uploader?.username,
            value.songwriters.takeIf { it.isNotEmpty() }?.joinToString(", ")).distinct().joinToString(" · ")
        stage.wake()
    }

    private fun use(mode: String) {
        if (this.mode == "browser" && mode != "browser") browser.stop()
        this.mode = mode
        media = MediaState()
        stage.artwork = if (mode == "demo") demoArtwork() else null
        if (!preview) playback.use(mode)
        if (mode != "manual" && mode != "demo") {
            stage.track = null
            stage.playing = false
            stage.status = "Play something to get started"
            stage.detail = "Start music in another app or open an audio file."
            status.text = if (mode == "browser") "Browser companion" else "Windows media"
            handleTrack(null)
            stage.wake()
        }
    }

    private fun startManual(track: Track, demo: Boolean = false) {
        use(if (demo) "demo" else "manual")
        manualTrack = track
        manualPosition = 0
        manualAt = nowMs()
        manualPlaying = demo
        stage.track = track
        stage.playing = demo
        status.text = if (demo) "Demo" else "Manual sync"
        handleTrack(track)
        if (demo) showReady(demoLyrics(track == demoEncore))
        transport()
        stage.wake()
    }

    private fun showConnections() {
        val menu = PlayerMenu(coverTheme)
        fun entry(label: String, action: () -> Unit) = menu.choice(label, action = action)
        entry("Automatically follow Windows media") { use("") }
        entry("Choose a Windows media session…") {
            playback.sessions { sessions, error ->
                if (sessions.isEmpty()) {
                    message(error ?: "No active media sessions. Start playback in a music app or browser first.")
                } else {
                    val labels = sessions.map { it.name + if (it.playing) " · playing" else "" }
                    val drop = ChoiceBox(labels) { coverTheme }
                    if (sheets.confirm(frame, "Choose a media session", drop, "Connect")) {
                        use(sessions[drop.selectedIndex].id)
                    }
                }
            }
        }
        menu.addSeparator()
        entry("Open local audio…") { chooseFiles() }
        entry("Connect a browser tab…") { connectBrowser() }
        entry("Enter track manually…") { showManual() }
        entry("Try the lyrics demo") { startManual(demoTrack, true) }
        menu.show(connection, connection.width - menu.preferredSize.width, connection.height + 5)
    }

    private fun connectBrowser() {
        try {
            browser.start()
        } catch (error: Exception) {
            message(error.message ?: "Couldn't start the browser companion.")
            return
        }
        use("browser")
        stage.status = "Waiting for a browser tab"
        stage.detail = "Open the companion extension and choose Follow this tab."
        val key = JTextField(browser.token, 35).apply { isEditable = false }
        val instructions = box(BoxLayout.Y_AXIS).apply {
            add(textLabel("Load the browser-companion folder as an unpacked Chrome or Edge extension."))
            add(textLabel("Open the extension on the music tab and paste this pairing key."))
            add(Box.createVerticalStrut(9))
            add(key)
        }
        val choice = sheets.choose(frame, "Connect a browser tab", instructions, "Copy key", "Close")
        if (choice == 0) Toolkit.getDefaultToolkit().systemClipboard.setContents(
            java.awt.datatransfer.StringSelection(browser.token), null)
    }

    private fun showManual() {
        val title = JTextField(22)
        val artist = JTextField(22)
        val album = JTextField(22)
        val length = JTextField("3:30", 8)
        track?.takeUnless { mode == "demo" }?.let {
            title.text = it.title
            artist.text = it.artist
            album.text = it.album
            if (it.duration > 0) length.text = clockText(it.duration)
        }
        val panel = box(BoxLayout.Y_AXIS).apply {
            add(textLabel("Track title")); add(title); add(textLabel("Artist")); add(artist)
            add(textLabel("Album (optional)")); add(album); add(textLabel("Duration (m:ss)")); add(length)
        }
        if (!sheets.confirm(frame, "Manual sync", panel, "Load track")) return
        val parts = length.text.trim().split(':').mapNotNull(String::toLongOrNull)
        val seconds = when (parts.size) { 2 -> parts[0] * 60 + parts[1]; 1 -> parts[0]; else -> 0 }
        if (title.text.isBlank() || seconds !in 1..86_400) {
            message("Enter a track title and a valid duration.")
            return
        }
        startManual(Track(title.text.trim(), artist.text.trim(), album.text.trim(), seconds * 1000))
        toast("Press Play here when your other player starts.")
    }

    private fun chooseFiles() {
        val extensions = setOf("mp3", "wav", "flac", "m4a", "aac", "wma", "ogg", "opus", "mp4", "lrc", "ttml", "txt")
        val files = pickFiles(frame, "Open audio or lyrics", extensions, multiple = true)
        if (files.isNotEmpty()) openFiles(files)
    }

    private fun openFiles(files: List<File>) {
        val lyrics = files.firstOrNull { it.extension.lowercase() in setOf("lrc", "ttml", "txt") }
        val audio = files.filter { it.isFile && it.extension.lowercase() !in setOf("lrc", "ttml", "txt") }
        if (audio.isEmpty()) {
            if (lyrics != null && track != null) importLyrics(lyrics, false)
            else message("Start a track or enter its details before importing lyrics.")
            return
        }
        playback.open(audio) { error ->
            if (error != null) { message(error); return@open }
            if (mode == "browser") browser.stop()
            this.mode = "local"
            status.text = "Local audio"
            media = MediaState()
            if (lyrics != null) Timer(450) { if (track != null) importLyrics(lyrics, false) }.apply { isRepeats = false; start() }
        }
    }

    private fun duration() = track?.duration ?: 0L
    private fun position(now: Long = nowMs()): Long {
        val pos = if (mode in setOf("manual", "demo")) {
            manualPosition + if (manualPlaying) now - manualAt else 0
        } else media.position + if (media.playing && media.clock) ((now - media.at) * media.rate).toLong() else 0
        val duration = duration()
        return if (duration > 0) pos.coerceIn(0, duration) else pos.coerceAtLeast(0)
    }

    private fun offset() = settings.value.let { it.globalOffset + (track?.key?.let(it.offsets::get) ?: 0) }
    private fun lyricPosition() = (position() + offset()).coerceAtLeast(0)
    private fun jumpToLyric(target: Long) = seekTo((target - offset()).coerceAtLeast(0))
    private fun seekTo(value: Long) {
        if (track == null) return
        val target = if (duration() > 0) value.coerceIn(0, duration()) else value.coerceAtLeast(0)
        if (mode in setOf("manual", "demo")) {
            manualPosition = target
            manualAt = nowMs()
            stage.wake()
        } else if (mode == "browser" && media.seek) {
            if (!browser.control("seek", target)) toast("The browser tab has disconnected.")
        } else if (media.seek) playback.control("seek", target) { if (!it) toast("The player declined the seek.") }
        else toast("This player does not expose seeking.")
        transport()
    }

    private fun control(action: String) {
        if (track == null) return
        if (mode == "demo" && action == "next" && track == demoTrack) {
            startManual(demoEncore, true)
            return
        }
        if (mode == "demo" && action == "previous" && track == demoEncore) {
            startManual(demoTrack, true)
            return
        }
        if (mode in setOf("manual", "demo")) {
            if (action != "toggle") return
            manualPosition = position().takeUnless { it == duration() } ?: 0L
            manualAt = nowMs()
            manualPlaying = !manualPlaying
            stage.playing = manualPlaying
        } else if (mode == "browser") {
            if (!browser.control(action)) toast("The browser tab has disconnected.")
        } else playback.control(action) { if (!it) toast("The player declined this control.") }
        transport()
        stage.wake()
    }

    private fun transport() {
        if (mode == "browser" && media.track != null && nowMs() - browser.lastReport > 3500) {
            media = MediaState()
            stage.artwork = null
            stage.track = null
            stage.playing = false
            handleTrack(null)
            stage.status = "Browser tab disconnected"
            stage.detail = "Open the browser companion and choose Follow this tab."
            stage.wake()
        }
        val duration = duration()
        val pos = position()
        val manual = mode in setOf("manual", "demo")
        if (manual && manualPlaying && duration > 0 && pos >= duration) {
            if (mode == "demo" && track == demoTrack) {
                startManual(demoEncore, true)
                return
            }
            manualPlaying = false
            manualPosition = duration
            stage.playing = false
        }
        val playing = if (manual) manualPlaying else media.playing
        val label = if (playing) "pause" else "play"
        if (play.symbol != label) play.symbol = label
        val playLabel = if (playing) "Pause (Space)" else "Play (Space)"
        if (play.toolTipText != playLabel) {
            play.toolTipText = playLabel
            play.accessibleContext.accessibleName = playLabel
        }
        refreshPresentation()
        footerFrame.isVisible = track != null && !stage.presentation
        if (track == null) {
            homeTitle.text = when (stage.status) {
                "Play something to get started" -> "Play a song"
                else -> stage.status
            }
            homeDetail.text = when (stage.status) {
                "Windows media unavailable" -> "Use the browser companion, or enter a track manually."
                "Play something to get started" -> "Start music in another app or open an audio file."
                else -> stage.detail
            }
        }
        play.isEnabled = track != null && (manual || media.toggle)
        backward.isEnabled = !manual && media.previous || mode == "demo" && track == demoEncore
        forward.isEnabled = !manual && media.next || mode == "demo" && track == demoTrack
        stage.upcoming = when {
            !settings.value.showUpNext || !nextUpDue(pos, duration) -> null
            mode == "demo" && track == demoTrack -> demoUpcoming
            else -> media.upcoming
        }
        sync.isEnabled = track != null
        timeline.isEnabled = duration > 0 && (manual || media.seek)
        val shown = if (dragging) duration * timeline.value / 1000 else pos
        if (!dragging) {
            sliderUpdate = true
            timeline.value = if (duration > 0) (1000.0 * shown / duration).roundToInt() else 0
            sliderUpdate = false
        }
        elapsed.text = clockText(shown)
        total.text = if (duration > 0) clockText(duration) else "–:––"
        if (!manual && track != null && !media.clock && !stage.loading) {
            elapsed.toolTipText = "The player is not reporting a playback position."
        } else elapsed.toolTipText = null
    }

    private fun toggleCover() {
        if (track == null || mini) return
        change { it.copy(fullCover = !it.fullCover) }
    }

    fun applyAppearance() {
        stage.settings = settings.value
        UiMotion.reduced = stage.settings.reduceMotion
        refreshPresentation()
        if (!mini) frame.isAlwaysOnTop = settings.value.alwaysOnTop
        ready?.let(::showReady)
        stage.wake()
    }

    fun change(reload: Boolean = false, edit: (Settings) -> Settings) {
        try {
            settings.update(edit)
            applyAppearance()
            if (reload) lookup()
        } catch (error: Exception) {
            message(error.message ?: "Couldn't save settings.")
        }
    }

    private fun toggleMini() {
        if (maximizedBounds != null) fullscreen()
        if (!mini) {
            restoreMaximized = chrome.maximized
            if (restoreMaximized) chrome.toggleMaximized()
        }
        mini = !mini
        stage.mini = mini
        stage.resetScroll()
        compact.symbol = if (mini) "expand" else "mini"
        val label = if (mini) "Restore window (Ctrl+M)" else "Mini player (Ctrl+M)"
        compact.toolTipText = label
        compact.accessibleContext.accessibleName = label
        frame.isAlwaysOnTop = mini || settings.value.alwaysOnTop
        frame.minimumSize = if (mini) Dimension(370, 230) else Dimension(430, 350)
        if (mini) {
            previousBounds = frame.bounds
            frame.setSize(500, 305)
        } else {
            previousBounds?.let { frame.bounds = it }
            if (restoreMaximized) chrome.toggleMaximized()
        }
        chrome.refresh()
        responsiveChrome()
    }

    private fun fullscreen() {
        val saved = maximizedBounds
        val device = frame.graphicsConfiguration.device
        if (saved == null) {
            maximizedBounds = frame.bounds
            fullScreenDevice = device
            chrome.fullscreen = true
            device.fullScreenWindow = frame
        } else {
            fullScreenDevice?.fullScreenWindow = null
            fullScreenDevice = null
            frame.bounds = saved
            maximizedBounds = null
            chrome.fullscreen = false
        }
        transport()
        responsiveChrome()
    }

    private fun openSettings() {
        val current = settingsWindow ?: SettingsDialog(this).also { settingsWindow = it }
        current.show()
    }

    private fun showLyricsMenu() {
        val menu = PlayerMenu(coverTheme)
        val current = track
        val remote = current != null && mode != "demo"
        val match = displayedSelection
        menu.heading("Lyrics", when {
            match != null -> "${match.source.displayName} · ${match.quality.displayLabel()}"
            mode == "demo" -> "Demo lyrics"
            else -> current?.title ?: "Play a track to find lyrics"
        })
        menu.choice("Choose source…", remote, symbol = "sources", action = ::chooseProvider)
        menu.choice("Reload lyrics", current != null, symbol = "sync") { lookup(refresh = true) }
        menu.choice(if (settings.value.fullCover) "Show lyrics" else "Show cover", current != null && !mini, symbol = "lyrics") { toggleCover() }
        menu.addSeparator()
        menu.choice("Import for this play…", current != null, symbol = "folder") { chooseLyrics(false) }
        menu.choice("Save lyrics for this song…", current != null, symbol = "folder") { chooseLyrics(true) }
        menu.choice("Manage saved lyrics…", symbol = "lyrics", action = ::manageLyrics)
        menu.addSeparator()
        menu.choice("Set Spotify track ID…", remote, symbol = "settings", action = ::spotifyOverride)
        menu.choice("Generate word timings…", remote, symbol = "wave", action = ::modelSync)
        menu.show(import, import.width - menu.preferredSize.width, -menu.preferredSize.height - 5)
    }

    private fun spotifyOverride() {
        val current = track ?: return
        val value = sheets.input(frame, "Spotify track ID", "Paste a track ID or link. Leave blank to reset.",
            settings.value.spotifyIds[current.key].orEmpty())?.trim() ?: return
        val id = value.substringAfter("open.spotify.com/track/", value).substringBefore('?').substringBefore('#').substringAfterLast(':')
        if (id.isNotBlank() && !id.matches(Regex("[A-Za-z0-9]{22}"))) {
            message("Enter a 22-character Spotify track ID or a valid track link.")
            return
        }
        change(true) { it.copy(spotifyIds = if (id.isBlank()) it.spotifyIds - current.key else it.spotifyIds + (current.key to id)) }
    }

    private fun chooseProvider() {
        val current = track ?: return
        val config = settings.value
        val imported = temporary
        val selection = displayedSelection
        val sources = engine.enabledProviders(config).map { it.id to it.displayName }
        val picker = LyricsSourcePicker(current, sources, selectedProvider, selection?.source?.id) { coverTheme }
        sourcePicker = picker
        val known = selection?.takeIf { it.source.id == "local" || sources.any { source -> source.first == it.source.id } }
        if (known != null) picker.update(known.source.id, RemoteLyricsResolution.Found(known, emptyList()))
        val scan = scope.launch(Dispatchers.IO) {
            val slots = Semaphore(4)
            for (id in listOf("local") + sources.map { it.first }) {
                if (id == known?.source?.id) continue
                launch {
                    val result = slots.withPermit {
                        try {
                            engine.resolve(current, config, imported, id) {}
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            RemoteLyricsResolution.Unavailable(listOf(
                                ProviderAttempt(id, ProviderAttemptOutcome.UNAVAILABLE, message = error.message),
                            ))
                        }
                    }
                    onUi { picker.update(id, result) }
                }
            }
        }
        val answer = try {
            sheets.choose(frame, "Choose lyrics source", picker, "Use selected source", "Cancel",
                primaryState = picker.primaryState)
        } finally {
            sourcePicker = null
            scan.cancel()
        }
        if (answer != 0) return
        if (track?.key != current.key || track?.file != current.file) {
            toast("The song changed. Open the source picker for the current track.")
            return
        }
        selectedProvider = picker.selectedId
        lookup(prefetched = picker.selectedLyrics)
    }

    private fun modelSync() {
        val current = track ?: return
        if (!sheets.confirm(frame, "Generate word timings", "Ask lrc.red to look up this song and generate word timings?", "Generate")) return
        toast("Waiting for lrc.red…")
        scope.launch(Dispatchers.IO) {
            val result = engine.modelSync(current)
            onUi {
                if (track?.key != current.key) return@onUi
                when (result) {
                    is ModelSyncResult.Failed -> message(result.message)
                    is ModelSyncResult.Synced -> {
                        val tt = result.payload.ttmlLyrics
                        if (tt != null) temporary = tt
                        selectedProvider = null
                        lookup()
                    }
                }
            }
        }
    }

    private fun chooseLyrics(persistent: Boolean) {
        pickFiles(frame, "Open lyric file", setOf("ttml", "lrc", "txt")).firstOrNull()?.let { importLyrics(it, persistent) }
    }

    private fun importLyrics(file: File, persistent: Boolean) {
        val current = track ?: return
        try {
            val raw = readLyricFile(file)
            requireNotNull(engine.asSelection(raw, "Imported lyrics")) { "The lyric file contains no readable lyrics." }
            if (persistent) {
                val request = current.lookup()
                engine.local.put(request.title, request.artist, current.title, current.artist, current.album, raw)
                temporary = null
            } else temporary = raw
            selectedProvider = null
            lookup()
        } catch (error: Exception) {
            message(error.message ?: "Couldn't import this lyric file.")
        }
    }

    fun manageLyrics() {
        val saved = engine.local.entries()
        if (saved.isEmpty()) { message("No songs saved yet. Use Lyrics → Save lyrics for this song."); return }
        val list = JList(saved.map { it.title + "  ·  " + it.artist }.toTypedArray()).apply { visibleRowCount = 9; selectedIndex = 0 }
        val owner = dialogOwner
        val result = sheets.choose(owner, "Lyrics Manager", JScrollPane(list), "Close", "Export", "Remove")
        val entry = saved.getOrNull(list.selectedIndex) ?: return
        if (result == 1) {
            val raw = engine.local.raw(entry.key) ?: return
            val extension = if (raw.trimStart().startsWith("<")) "ttml" else if (raw.trimStart().startsWith("[")) "lrc" else "txt"
            val file = chooseSave("lyrics.$extension") ?: return
            runCatching { atomicText(file, raw) }.onFailure { message(it.message ?: "Couldn't export lyrics.") }
        }
        if (result == 2 && sheets.confirm(owner, "Remove lyrics", "Remove the saved lyrics for ${entry.title}?", "Remove")) {
            engine.local.remove(entry.key)
            lookup()
        }
    }

    private fun editTiming() {
        val current = track ?: return
        val value = numberControl({ coverTheme }, settings.value.offsets[current.key] ?: 0, -15_000, 15_000, 50)
        val panel = box(BoxLayout.Y_AXIS).apply {
            add(textLabel("Song offset in milliseconds. Positive values show lyrics earlier.", 12))
            add(Box.createVerticalStrut(8))
            add(value)
        }
        val result = sheets.choose(frame, "Sync lyrics", panel, "Apply", "Reset", "Cancel")
        if (result == 0 || result == 1) {
            change { it.copy(offsets = if (result == 1) it.offsets - current.key else it.offsets + (current.key to (value.value as Int))) }
        }
    }

    fun chooseSave(name: String): File? {
        val owner = dialogOwner
        val file = pickFiles(owner, "Save file", suggestedName = name).firstOrNull() ?: return null
        return file.takeIf { !it.exists() || sheets.confirm(owner, "Replace file", "Replace this file?", "Replace") }
    }

    fun message(value: String) = sheets.info(dialogOwner, APP_TITLE, value)

    fun toast(value: String) {
        stage.notify(value)
    }
}

fun clockText(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

val demoTrack = Track("Night Signals", "Spicy Lyrics Demo", duration = 42_000)
val demoEncore = Track("The Last Light", "Spicy Lyrics Demo", duration = 42_000)
private val demoUpcoming = UpcomingTrack(demoEncore.title, demoEncore.artist)
fun demoLyrics(encore: Boolean = false): LyricsState.Ready {
    val lines = listOf(
        2_100L to "The streets are filling with violet light",
        6_100L to "I let the city breathe",
        10_100L to "A signal travels through the dark",
        14_000L to "We found a rhythm in the silence",
        18_400L to "And kept it beating through the night",
        22_800L to "The colors fall into the distance",
        27_200L to "But I can still see every light",
        32_000L to "So let the quiet carry us home",
        36_400L to "And keep that little signal bright",
    ).mapIndexed { index, (start, value) ->
        var cursor = start
        val tokens = (if (encore) value.replace("light", "rain").replace("city", "river") else value).split(' ')
        val phrase = if (index == 5) 1_400L else 3_550L
        val words = tokens.map {
            val length = if (it == "rhythm") 1_650L else (phrase - if (index == 3) 1_650 else 0) /
                (tokens.size - if (index == 3) 1 else 0)
            TimedWord(it, cursor, cursor + length, false).also { cursor += length }
        }
        TimedLine(start, cursor, words, groupId = index)
    }
    val content = lines.toMutableList().apply {
        add(0, TimedLine(0, 2_100, emptyList(), role = LineRole.INTERLUDE))
        add(5, TimedLine(16_550, 17_950, listOf(
            TimedWord("In", 16_550, 16_830, false),
            TimedWord("the", 16_830, 17_100, false),
            TimedWord("silence", 17_100, 17_950, false)),
            role = LineRole.BACKGROUND, groupId = 3, oppositeAligned = true))
    }
    return LyricsState.Ready(content, "Spicy Lyrics Demo", null, null, emptyList(), "Spicy Lyrics", LyricsType.Syllable)
}
