package spicy.desktop

import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.providers.CustomLyricsSource
import com.tx24.spicyplayer.translation.TranslationLanguages
import com.tx24.spicyplayer.translation.TranslationProvider
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.UUID
import javax.swing.*
import javax.swing.border.EmptyBorder

class SettingsDialog(private val app: DesktopApp) {
    private val dialog = JDialog(app.frame, "$APP_TITLE Settings", false).apply { isUndecorated = true }
    private val colors = CoverTheme(Color(26, 26, 26), listOf(Color(21, 21, 21)), Color(224, 224, 224))
    private val secondary = Color(185, 185, 185)
    private val theme = { colors }
    private val sheets = DialogSheets(theme)
    private val chrome = WindowChrome(dialog, theme)
    private val cards = AnimatedCards()
    private val sourceLayout = box(BoxLayout.Y_AXIS).apply { isOpaque = false }
    private lateinit var timingPage: JScrollPane
    private var focusedOrigin: String? = null
    private var currentPage = "Lyrics"

    internal fun refreshTheme() { if (dialog.isVisible) dialog.repaint() }
    internal fun dispose() = dialog.dispose()

    fun show() {
        if (dialog.isVisible) {
            dialog.toFront()
            dialog.requestFocus()
            return
        }
        dialog.contentPane = content()
        if (dialog.width == 0) dialog.setSize(908, 670)
        dialog.minimumSize = Dimension(690, 430)
        dialog.setLocationRelativeTo(app.frame)
        dialog.defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE
        dialog.rootPane.registerKeyboardAction({ dialog.isVisible = false }, KeyStroke.getKeyStroke("ESCAPE"),
            JComponent.WHEN_IN_FOCUSED_WINDOW)
        dialog.isVisible = true
    }

    internal fun content(): JPanel {
        cards.removeAll()
        timingPage = scroll(timing())
        val sections = linkedMapOf(
            "Lyrics" to ("lyrics" to scroll(lyricAppearance())),
            "Appearance" to ("wave" to scroll(appearance())),
            "Sources" to ("sources" to scroll(sourceLayout)),
            "Lyrics & sync" to ("sync" to timingPage),
            "Advanced" to ("settings" to scroll(advanced())),
            "About" to ("info" to scroll(about())),
        )
        sections.forEach { (title, tab) -> cards.add(tab.second, title) }
        sourceRows()
        val choices = box(BoxLayout.Y_AXIS).apply {
            background = Color(20, 20, 20)
            border = EmptyBorder(20, 13, 14, 13)
            preferredSize = Dimension(197, 1)
        }
        val brand = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 32)
            add(playerMark(27, theme))
            add(textLabel("Spicy Lyrics Desktop", 12, true, secondary))
        }
        val title = textLabel("Settings", 25, true).apply {
            border = EmptyBorder(4, 8, 18, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        choices.add(brand)
        choices.add(title)
        val navigation = mutableListOf<PlayerButton>()
        for ((label, tab) in sections) {
            if (label == "Advanced") {
                choices.add(Box.createVerticalStrut(14))
                choices.add(JSeparator().apply {
                    foreground = colors.accent.alpha(.23)
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, 1)
                })
                choices.add(Box.createVerticalStrut(11))
            }
            val nav = PlayerButton(label, tab.first, theme, size = 38, alignLeft = true) {
                currentPage = label
                cards.showCard(label)
                navigation.forEach { it.isSelected = it.text == label }
            }.apply {
                isSelected = label == currentPage
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 38)
            }
            navigation += nav
            choices.add(nav)
            choices.add(Box.createVerticalStrut(3))
        }
        cards.showCard(currentPage)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(2, 0, 0, 5)
            add(chrome.buttons, BorderLayout.EAST)
        }
        chrome.dragFrom(header, choices, brand, title, *brand.components)
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(cards, BorderLayout.CENTER)
        }
        return object : JPanel(BorderLayout()) {
            override fun paintComponent(graphics: Graphics) {
                (graphics.create() as Graphics2D).useGraphics {
                    it.paint = java.awt.GradientPaint(0f, 0f, Color(31, 31, 31),
                        width.toFloat(), height.toFloat(), Color(22, 22, 22))
                    it.fillRect(0, 0, width, height)
                }
            }
        }.apply {
            add(choices, BorderLayout.WEST)
            add(body, BorderLayout.CENTER)
        }
    }

    private fun scroll(content: JPanel) = styleScroll(JScrollPane(content), theme)

    private fun page() = box(BoxLayout.Y_AXIS).apply { isOpaque = false; border = EmptyBorder(22, 24, 24, 21) }

    private fun lyricAppearance(): JPanel = page().apply {
        val v = app.settings.value
        heading(this, "Lyrics")
        val text = group(this, "Text")
        val value = textLabel("${v.textScale}%", 13, true, colors.accent)
        val scale = PlaybackSlider(theme, 65, 165, v.textScale, persistentThumb = true).apply {
            accessibleContext.accessibleName = "Lyric size"
            toolTipText = null
            addChangeListener {
                value.text = "${this.value}%"
                if (!valueIsAdjusting && this.value != app.settings.value.textScale) app.change { it.copy(textScale = this.value) }
            }
        }
        val size = JPanel(BorderLayout(0, 3)).apply {
            isOpaque = false
            border = EmptyBorder(10, 13, 11, 14)
            add(rowLabel("Lyric size", "Adjust the size of lead lyrics, romanization and backing vocals."), BorderLayout.NORTH)
            add(scale, BorderLayout.CENTER)
            add(value, BorderLayout.SOUTH)
        }
        text.add(size)

        val style = group(this, "Renderer")
        row(style, "Renderer", select(listOf("Spring", "Bloom"), v.style) { choice -> app.change { it.copy(style = choice) } },
            "Spring is the default. Bloom uses a different word highlight and scroll animation.")
        row(style, "Lyric glow", checkbox(v.lyricGlow) { on -> app.change { it.copy(lyricGlow = on) } })
        row(style, "Blur distant lyrics", checkbox(v.distanceBlur) { on -> app.change { it.copy(distanceBlur = on) } })
        row(style, "Fade previous lyrics", checkbox(v.hidePrevious) { on -> app.change { it.copy(hidePrevious = on) } })
        row(style, "Wait bubbles", checkbox(v.waitBubbles) { on -> app.change { it.copy(waitBubbles = on) } },
            "Show animated dots during longer instrumental gaps.")
        row(style, "Auto-scroll plain lyrics", checkbox(v.plainAutoScroll) { on -> app.change { it.copy(plainAutoScroll = on) } })
        val fonts = group(this, "Fonts")
        row(fonts, "Lead lyric font", fontButton(false))
        row(fonts, "Backing vocal font", fontButton(true))
    }

    private fun appearance(): JPanel = page().apply {
        val v = app.settings.value
        heading(this, "Appearance")
        val visuals = group(this, "Background and motion")
        row(visuals, "Background", select(listOf("Motion", "Cover blur", "Solid"), v.background) { choice -> app.change { it.copy(background = choice) } },
            "Choose how the current track’s artwork colors the player.")
        row(visuals, "Reduce motion", checkbox(v.reduceMotion) { on -> app.change { it.copy(reduceMotion = on) } })
        val layout = group(this, "Player layout")
        row(layout, "Fullscreen listening layout", checkbox(v.fullscreenStage) { on -> app.change { it.copy(fullscreenStage = on) } })
        row(layout, "Show Up Next", checkbox(v.showUpNext) { on -> app.change { it.copy(showUpNext = on) } })
        row(layout, "Cover stage", checkbox(v.coverStage) { on -> app.change { it.copy(coverStage = on) } })
        row(layout, "Cover on the right", checkbox(v.coverRight) { on -> app.change { it.copy(coverRight = on) } })
        row(layout, "Show lyrics credits", checkbox(v.credits) { on -> app.change { it.copy(credits = on) } })
        row(layout, "Always on top", checkbox(v.alwaysOnTop) { on -> app.change { it.copy(alwaysOnTop = on) } })
    }

    private fun timing(): JPanel = page().apply {
        val v = app.settings.value
        heading(this, "Lyrics and timing")
        row(this, "Global timing offset (ms)", spinner(v.globalOffset, -15_000, 15_000, 50) { offset -> app.change { it.copy(globalOffset = offset) } })
        row(this, "Split background vocals", checkbox(v.backgroundVocals) { on -> app.change(true) { it.copy(backgroundVocals = on) } })
        row(this, "Show romanization", checkbox(v.romanization) { on -> app.change(true) { it.copy(romanization = on) } })
        row(this, "Japanese as written", checkbox(v.writtenJapanese) { on -> app.change(true) { it.copy(writtenJapanese = on) } })
        row(this, "Human romanizations", checkbox(v.humanRomanization) { on ->
            if (!on || allow(SourceDisclosures.forId(SourceDisclosures.GENIUS_ROMANIZATION_ID)?.description.orEmpty())) {
                app.change(true) { it.copy(humanRomanization = on) }
            }
        })
        row(this, "Show translations", checkbox(v.translation) { on ->
            val config = app.settings.value
            val provider = TranslationProvider.stored(config.translator)
            val granted = on && allow(SourceDisclosures.translation(provider, config.humanTranslation, translationHost(config.translationEndpoint)).description)
            app.change(true) { it.copy(translation = on, translationConsent = granted) }
        })
        val languages = TranslationLanguages.common.toList()
        val codes = languages.map { it.first }
        val language = ChoiceBox(languages.map { it.second }, theme).apply {
            selectedIndex = codes.indexOf(v.translationLanguage).coerceAtLeast(0)
            addActionListener {
                val code = codes.getOrNull(selectedIndex) ?: return@addActionListener
                onUi { if (code != app.settings.value.translationLanguage) app.change(true) { it.copy(translationLanguage = code) } }
            }
        }
        row(this, "Translation language", language)
        row(this, "Translation service", select(listOf("Google", "DeepL", "OpenAi", "Gemini"), v.translator) { service ->
            val config = app.settings.value
            if (service == config.translator) return@select
            val granted = config.translation && allow(SourceDisclosures.translation(TranslationProvider.stored(service),
                config.humanTranslation, translationHost(config.translationEndpoint)).description)
            app.change(true) { it.copy(translator = service, translationKey = "", translationConsent = granted) }
        })
        row(this, "Try Genius human translations", checkbox(v.humanTranslation) { on ->
            val config = app.settings.value
            val granted = config.translationConsent && (!on || allow(SourceDisclosures.translation(
                TranslationProvider.stored(config.translator), true, translationHost(config.translationEndpoint)).description))
            app.change(true) { it.copy(humanTranslation = on, translationConsent = granted) }
        })
        row(this, "Translation API settings", button("Configure") {
            val config = app.settings.value
            val field = JPasswordField(config.translationKey, 27)
            val endpoint = JTextField(config.translationEndpoint, 27)
            val model = JTextField(config.translationModel, 27)
            val form = page().apply {
                add(textLabel("API key")); add(field)
                add(textLabel("API base URL (OpenAI-compatible)")); add(endpoint)
                add(textLabel("Model (OpenAI-compatible or Gemini)")); add(model)
            }
            if (sheets.confirm(dialog, "Translation API settings", form, "Save")) {
                val base = endpoint.text.trim()
                if (base.isNotEmpty() && runCatching {
                        val uri = java.net.URI(base)
                        uri.host != null && uri.userInfo == null &&
                            (uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))
                    }.getOrDefault(false).not()) {
                    app.message("Use an HTTPS URL or a local HTTP service.")
                } else {
                    val provider = TranslationProvider.stored(config.translator)
                    val newHost = provider == TranslationProvider.OpenAi && translationHost(base) != translationHost(config.translationEndpoint)
                    val granted = if (newHost && config.translation) {
                        allow(SourceDisclosures.translation(provider, config.humanTranslation, translationHost(base)).description)
                    } else config.translationConsent
                    val entered = String(field.password).trim()
                    val apiKey = entered.takeUnless { newHost && it == config.translationKey }.orEmpty()
                    app.change(true) { it.copy(translationKey = apiKey, translationEndpoint = base,
                        translationModel = model.text.trim(), translationConsent = granted) }
                }
            }
        })
        row(this, "Linked lyrics folder", button(if (v.lyricsFolder.isEmpty()) "Choose folder" else File(v.lyricsFolder).name) {
            val choice = sheets.choose(dialog, "Linked lyrics folder", "Find matching LRC or TTML files in a folder and its subfolders.",
                "Choose folder", "Unlink", "Cancel")
            if (choice == 0) {
                val initial = v.lyricsFolder.takeIf(String::isNotBlank)?.let(::File) ?: File(System.getProperty("user.home"))
                sheets.folder(dialog, initial)?.let { folder ->
                    app.change(true) { it.copy(lyricsFolder = folder.absolutePath) }
                    timingPage.setViewportView(timing())
                }
            } else if (choice == 1) {
                app.change(true) { it.copy(lyricsFolder = "") }
                timingPage.setViewportView(timing())
            }
        })
        row(this, "Saved song lyrics", button("Open Lyrics Manager") { app.manageLyrics() })
    }

    private fun sourceRows() {
        sourceLayout.removeAll()
        sourceLayout.border = EmptyBorder(13, 25, 22, 25)
        val config = app.settings.value
        val descriptors = app.engine.descriptors(config)
        val byId = descriptors.associateBy { it.id }
        val ranking = SourceRanking(descriptors, config.policy)
        heading(sourceLayout, "Lyrics sources")
        sourceLayout.add(textLabel("Source order breaks ties between matches with the same timing.", 12, color = MUTED))
        sourceLayout.add(Box.createVerticalStrut(10))
        ranking.origins.forEachIndexed { index, origin ->
            val active = LyricsSourceSettings.isOn(ranking, origin.id)
            val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(PlayerButton("", "up", { colors }, size = 30, name = "Move source up") { move(ranking, index, -1) }
                    .apply { isEnabled = index > 0 })
                add(PlayerButton("", "down", { colors }, size = 30, name = "Move source down") { move(ranking, index, 1) }
                    .apply { isEnabled = index < ranking.origins.lastIndex })
                add(button(if (focusedOrigin == origin.id) "Hide" else "Details") {
                    focusedOrigin = if (focusedOrigin == origin.id) null else origin.id
                    sourceRows()
                })
                add(checkbox(active) { selected ->
                    val first = LyricsSourceSettings.providerToSwitchOn(ranking, origin.id)
                    if (selected && first != null && !allowProvider(byId[first])) {
                        sourceRows()
                    } else updatePolicy { policy, providers -> LyricsSourceSettings.setSourceEnabled(policy, providers, origin.id, selected) }
                }.apply { accessibleContext.accessibleName = origin.displayName })
            }
            row(sourceLayout, origin.displayName, actions)
            if (focusedOrigin == origin.id) {
                val card = box(BoxLayout.Y_AXIS).apply {
                    isOpaque = false
                    border = EmptyBorder(5, 16, 7, 11)
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                }
                origin.providerIds.forEach { id ->
                    val descriptor = byId[id] ?: LyricsBlends.byId(id)?.descriptor ?: return@forEach
                    row(card, descriptor.displayName, checkbox(ranking.pairOn(origin.id, id)) { on ->
                        if (!on || allowProvider(descriptor)) {
                            updatePolicy { policy, providers ->
                                LyricsSourceSettings.setPairEnabled(policy, providers, SourcePair(origin.id, id), on)
                            }
                        } else sourceRows()
                    })
                }
                origin.qualities.forEach { quality ->
                    val name = when (quality) {
                        RemoteLyricsQuality.WORD_SYNCED -> "Word-synced lyrics"
                        RemoteLyricsQuality.LINE_SYNCED -> "Line-synced lyrics"
                        else -> "Plain lyrics"
                    }
                    val allowed = origin.qualities.toSet() - config.policy.syncTypesOff[origin.id].orEmpty()
                    row(card, name, checkbox(quality in allowed) { on ->
                        updatePolicy { policy, providers ->
                            LyricsSourceSettings.setSyncTypes(policy, providers, origin.id, if (on) allowed + quality else allowed - quality)
                        }
                    })
                }
                config.custom.firstOrNull { it.id == origin.id }?.let { source ->
                    row(card, "Custom source", button("Edit or remove") { editCustom(source) })
                }
                sourceLayout.add(card)
            }
        }
        sourceLayout.add(Box.createVerticalStrut(13))
        row(sourceLayout, "Custom HTTP source", button("Add source") { editCustom(null) })
        row(sourceLayout, "Spicy Lyrics publishable key", button(if (config.clientKey.isBlank()) "Set key" else "Change key") {
            val key = JPasswordField(config.clientKey, 26)
            val keyPanel = page().apply { add(textLabel("Enter an sl_pk_ client key.")); add(key) }
            if (sheets.confirm(dialog, "Spicy Lyrics API", keyPanel, "Save")) {
                val value = String(key.password).trim()
                if (value.isNotEmpty() && !value.startsWith("sl_pk_")) app.message("Use a publishable key beginning with sl_pk_.")
                else { app.change(true) { it.copy(clientKey = value) }; sourceRows() }
            }
        })
        sourceLayout.revalidate()
        sourceLayout.repaint()
    }

    private fun move(ranking: SourceRanking, index: Int, change: Int) {
        val order = ranking.origins.map { it.id }.toMutableList()
        java.util.Collections.swap(order, index, index + change)
        updatePolicy { policy, providers -> LyricsSourceSettings.reorder(policy, providers, order) }
    }

    private fun updatePolicy(edit: (RemoteLyricsPolicy, List<LyricsSourceDescriptor>) -> RemoteLyricsPolicy) {
        app.change(true) { it.copy(policy = edit(it.policy, app.engine.descriptors(it))) }
        sourceRows()
    }

    private fun allowProvider(provider: LyricsSourceDescriptor?) = provider?.let(SourceDisclosures::forSource)
        ?.let { allow(it.description) } ?: true

    private fun translationHost(value: String) = runCatching { java.net.URI(value).host }.getOrNull()

    private fun allow(text: String): Boolean {
        val content = JTextArea(text, 7, 43).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = WINDOW
            foreground = TEXT
            border = EmptyBorder(7, 8, 7, 8)
        }
        return sheets.confirm(dialog, "Share song data", content, "Allow")
    }

    private fun editCustom(existing: CustomLyricsSource?) {
        val name = JTextField(existing?.name.orEmpty(), 32)
        val url = JTextField(existing?.url.orEmpty(), 32)
        val path = JTextField(existing?.path.orEmpty(), 32)
        val headers = JTextArea(existing?.let { CustomLyricsSource.headersText(it.headers) }.orEmpty(), 3, 32)
        val form = page().apply {
            add(textLabel("Name")); add(name)
            add(textLabel("HTTPS URL template")); add(url)
            add(textLabel("{title}   {artist}   {album}   {duration}   {durationMs}   {spotifyId}", color = MUTED))
            add(textLabel("JSON path (optional)")); add(path)
            add(textLabel("Headers (optional, one Name: value per line)")); add(JScrollPane(headers))
        }
        val options = if (existing == null) arrayOf("Save", "Cancel", "Import shared source")
            else arrayOf("Save", "Cancel", "Copy shareable source", "Remove")
        val result = sheets.choose(dialog, "Custom lyrics source", form, *options)
        when (result) {
            0 -> {
                val value = url.text.trim()
                val problem = CustomLyricsSource.problem(value)
                if (problem != null) { app.message(problem); return }
                val source = CustomLyricsSource(existing?.id ?: CustomLyricsSource.newId(), name.text.trim().ifBlank { "Custom source" },
                    value, path.text.trim(), CustomLyricsSource.parseHeaders(headers.text), UUID.randomUUID().toString())
                storeCustom(existing, source)
            }
            2 -> if (existing != null) {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(CustomLyricsSource.share(existing)), null)
            } else {
                val shared = sheets.input(dialog, "Import a shared source", "Paste a shared source") ?: return
                val source = CustomLyricsSource.fromShare(shared) ?: run { app.message("The shared source is invalid."); return }
                storeCustom(null, source)
            }
            3 -> if (existing != null && sheets.confirm(dialog, "Remove source", "Remove ${existing.name}?", "Remove")) {
                storeCustom(existing, null)
            }
        }
    }

    private fun storeCustom(existing: CustomLyricsSource?, source: CustomLyricsSource?) {
        app.change(true) { cfg -> cfg.copy(custom = when {
            existing == null && source != null -> cfg.custom + source
            existing != null && source != null -> cfg.custom.map { if (it.id == existing.id) source else it }
            else -> cfg.custom.filterNot { it.id == existing?.id }
        }) }
        sourceRows()
    }

    private fun advanced(): JPanel = page().apply {
        heading(this, "Lookup and settings")
        row(this, "Last lookup", button("View diagnostics") {
            val names = (app.engine.descriptors() + LyricsBlends.ALL.map { it.descriptor }).associate { it.id to it.displayName }
            val summary = app.attempts.joinToString("\n") { attempt ->
                val name = names[attempt.sourceId] ?: attempt.sourceId
                val outcome = attempt.outcome.name.lowercase().replace('_', ' ')
                val quality = attempt.quality.takeUnless { it == RemoteLyricsQuality.NONE }?.name?.lowercase()?.replace('_', ' ')
                listOfNotNull(name, outcome, quality, attempt.failureCategory?.name?.lowercase()?.replace('_', ' ')).joinToString("   ·   ")
            }.ifBlank { "Start a track to see lookup results." }
            val area = JTextArea(summary, 17, 53).apply { isEditable = false; background = PANEL; foreground = TEXT }
            sheets.info(dialog, "Last lyrics lookup", JScrollPane(area))
        })
        row(this, "Online lyrics cache", button("Clear cache") { app.engine.clearCache(); app.lookup() })
        row(this, "Settings backup", button("Export") {
            app.chooseSave("spicy-lyrics-settings.json")?.let { file ->
                runCatching { app.settings.export(file) }.onFailure { app.message(it.message ?: "Couldn't export settings.") }
            }
        })
        row(this, "Restore settings", button("Import") {
            pickFiles(dialog, "Import settings backup", setOf("json")).firstOrNull()?.let { backup ->
                runCatching {
                    app.settings.restore(backup)
                    app.applyAppearance()
                    app.lookup()
                    dialog.isVisible = false
                }.onFailure { app.message(it.message ?: "Couldn't import settings.") }
            }
        })
    }

    private fun about(): JPanel = page().apply {
        heading(this, "About Spicy Lyrics Desktop")
        val desktop = group(this, "Desktop project")
        row(desktop, "Developed by elias4044", PlayerButton("GitHub", "external", theme, size = 34) {
            openProject("https://github.com/elias4044/spicy-lyrics-desktop")
        }, "github.com/elias4044/spicy-lyrics-desktop")
        val upstream = group(this, "Upstream credits")
        row(upstream, "Spicy Lyrics Mobile", PlayerButton("Source code", "external", theme, size = 34) {
            openProject("https://github.com/spicylyrics/mobile")
        }, "The shared lyric engine and the original mobile renderer.")
    }

    private fun openProject(url: String) {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
            .onFailure { app.message(url) }
    }

    private fun heading(panel: JPanel, text: String, size: Int = 28) {
        panel.add(textLabel(text, size, true))
        panel.add(Box.createVerticalStrut(16))
    }

    private fun group(panel: JPanel, name: String): JPanel {
        panel.add(textLabel(name, 13, true, secondary))
        panel.add(Box.createVerticalStrut(8))
        val group = object : JPanel() {
            override fun paintComponent(graphics: Graphics) {
                (graphics.create() as Graphics2D).useGraphics {
                    it.quality()
                    val shape = rounded(0, 0, width - 1, height - 1, 15)
                    it.color = colors.accent.alpha(.085)
                    it.fill(shape)
                    it.color = colors.accent.alpha(.19)
                    it.draw(shape)
                }
            }
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
            border = EmptyBorder(3, 1, 3, 1)
            putClientProperty("settings.group", true)
        }
        panel.add(group)
        panel.add(Box.createVerticalStrut(21))
        return group
    }

    private fun rowLabel(text: String, detail: String?): JPanel = JPanel(BorderLayout(0, 4)).apply {
        isOpaque = false
        add(textLabel(text, 14), if (detail == null) BorderLayout.CENTER else BorderLayout.NORTH)
        if (detail != null) add(JTextArea(detail).apply {
            setUI(javax.swing.plaf.basic.BasicTextAreaUI())
            isOpaque = false
            isEditable = false
            isFocusable = false
            lineWrap = true
            wrapStyleWord = true
            font = font(12)
            foreground = secondary
            border = null
        })
    }

    private fun row(panel: JPanel, text: String, control: JComponent, detail: String? = null) {
        val grouped = panel.getClientProperty("settings.group") == true
        val separated = grouped && panel.componentCount > 0
        val row = object : JPanel(BorderLayout(12, 0)) {
            override fun paintComponent(graphics: Graphics) {
                if (grouped) {
                    if (separated) {
                        graphics.color = colors.accent.alpha(.15)
                        graphics.drawLine(13, 0, width - 13, 0)
                    }
                } else {
                    graphics.color = colors.accent.alpha(.075)
                    graphics.fillRoundRect(0, 0, width, height, 14, 14)
                }
            }
        }.apply {
            isOpaque = false
            border = EmptyBorder(7, 13, 7, 12)
            alignmentX = Component.LEFT_ALIGNMENT
            add(rowLabel(text, detail), BorderLayout.CENTER)
            add(JPanel(java.awt.GridBagLayout()).apply {
                isOpaque = false
                add(control)
            }, BorderLayout.EAST)
            val height = if (detail == null) 52 else 70
            preferredSize = Dimension(0, height)
            minimumSize = Dimension(0, height)
            maximumSize = Dimension(Int.MAX_VALUE, height)
            if (control is SettingToggle || control is ChoiceBox || control is JSpinner) control.accessibleContext.accessibleName = text
        }
        panel.add(row)
        if (!grouped) panel.add(Box.createVerticalStrut(5))
    }

    private fun checkbox(initial: Boolean, action: (Boolean) -> Unit) = SettingToggle(initial, theme, action)

    private fun select(values: List<String>, initial: String, action: (String) -> Unit) = ChoiceBox(values, theme).apply {
        selectedItem = initial
        var previous = selectedItem
        addActionListener {
            val selected = selectedItem as? String ?: return@addActionListener
            if (selected != previous) {
                previous = selected
                onUi { action(selected) }
            }
        }
    }

    private fun spinner(initial: Int, min: Int, max: Int, step: Int, unit: String = "", action: (Int) -> Unit) =
        numberControl(theme, initial, min, max, step, unit).apply {
            addChangeListener { action(value as Int) }
        }

    private fun button(text: String, action: () -> Unit) = PlayerButton(text, "", theme, size = 34, action = action)

    private fun fontButton(backing: Boolean): PlayerButton {
        fun current() = with(app.settings.value) { if (backing) backingLyricFont else leadLyricFont }
        fun label() = LyricFonts.local(current())?.fontName?.take(26) ?: "Windows font"
        val title = if (backing) "Backing vocal font" else "Lead lyric font"
        lateinit var control: PlayerButton
        control = button(label()) {
            val choice = sheets.choose(dialog, title, "Choose a local font file in the weight you want to display.",
                "Choose file", "Use Windows font", "Cancel")
            val path = when (choice) {
                0 -> {
                    val file = pickFiles(dialog, title, setOf("ttf", "otf", "ttc")).firstOrNull() ?: return@button
                    if (LyricFonts.load(file) == null) {
                        app.message("Windows could not read this font file.")
                        return@button
                    }
                    file.absolutePath
                }
                1 -> ""
                else -> return@button
            }
            app.change { if (backing) it.copy(backingLyricFont = path) else it.copy(leadLyricFont = path) }
            control.text = label()
            control.revalidate()
        }
        control.accessibleContext.accessibleName = title
        return control
    }
}
