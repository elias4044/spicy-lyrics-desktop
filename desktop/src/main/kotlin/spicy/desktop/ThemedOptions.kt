package spicy.desktop

import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.*
import javax.swing.border.AbstractBorder
import javax.swing.border.EmptyBorder
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.plaf.basic.BasicComboBoxUI
import javax.swing.plaf.basic.BasicComboPopup
import javax.swing.plaf.basic.BasicFormattedTextFieldUI
import javax.swing.plaf.basic.BasicListUI
import javax.swing.plaf.basic.BasicPasswordFieldUI
import javax.swing.plaf.basic.BasicPopupMenuUI
import javax.swing.plaf.basic.BasicScrollBarUI
import javax.swing.plaf.basic.BasicScrollPaneUI
import javax.swing.plaf.basic.BasicSpinnerUI
import javax.swing.plaf.basic.BasicTextAreaUI
import javax.swing.plaf.basic.BasicTextFieldUI
import javax.swing.plaf.basic.ComboPopup
import javax.swing.text.JTextComponent
import kotlin.math.min

internal class ChoiceBox(values: List<String>, private val theme: () -> CoverTheme) : JComboBox<String>(values.toTypedArray()) {
    private val openMotion = MotionValue(this)
    private var arrow: PlayerButton? = null
    private var options: OptionsPopup? = null
    private val widestOption by lazy {
        val metrics = getFontMetrics(font)
        values.maxOfOrNull(metrics::stringWidth) ?: 0
    }

    init {
        font = font(13)
        foreground = TEXT
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        maximumRowCount = 8
        isLightWeightPopupEnabled = true
        border = EmptyBorder(0, 3, 0, 3)
        setUI(object : BasicComboBoxUI() {
            override fun createArrowButton() = iconButton("chevron", "Show options", theme).also { arrow = it }
            override fun createPopup(): ComboPopup = OptionsPopup(comboBox, theme) { widestOption }.also { options = it }
            override fun paint(graphics: Graphics, c: JComponent) {
                val g = graphics.create() as Graphics2D
                g.useGraphics {
                    it.quality()
                    val shape = rounded(1, 1, width - 2, height - 2, 11)
                    it.color = theme().accent.alpha(.085 + .095 * openMotion.value)
                    it.fill(shape)
                    it.color = theme().accent.alpha(if (hasFocus()) .72 else .20 + .52 * openMotion.value)
                    it.draw(shape)
                    it.font = font
                    it.color = Color.WHITE.alpha(if (isEnabled) .98 else .36)
                    val label = Stage.fit(it, selectedItem?.toString().orEmpty(), width - 47)
                    it.drawString(label, 13f, (height + it.fontMetrics.ascent - it.fontMetrics.descent) / 2f)
                }
            }
        })
        addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = popupState(true)
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) = popupState(false)
            override fun popupMenuCanceled(e: PopupMenuEvent) = popupState(false)
        })
        installSelectionKeys()
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
    }

    override fun getPreferredSize(): Dimension {
        val metrics = getFontMetrics(font)
        val max = (0 until itemCount).maxOfOrNull { metrics.stringWidth(getItemAt(it)) } ?: 0
        return Dimension((max + 55).coerceIn(151, 239), 36)
    }

    private fun popupState(opened: Boolean) {
        openMotion.to(if (opened) 1.0 else 0.0)
        arrow?.symbol = if (opened) "up" else "chevron"
        repaint()
    }

    private fun installSelectionKeys() {
        fun bind(key: String, name: String, action: () -> Unit) {
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name)
            actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) { if (isEnabled) action() }
            })
        }
        fun move(amount: Int) {
            if (!isPopupVisible) isPopupVisible = true
            val list = options?.list ?: return
            if (itemCount == 0) return
            val next = (list.selectedIndex + amount).coerceIn(0, itemCount - 1)
            list.selectedIndex = next
            list.ensureIndexIsVisible(next)
        }
        fun commit() {
            if (!isPopupVisible) { isPopupVisible = true; return }
            val index = options?.list?.selectedIndex ?: -1
            isPopupVisible = false
            if (index in 0 until itemCount && index != selectedIndex) selectedIndex = index
        }
        bind("ENTER", "commit option", ::commit)
        bind("SPACE", "open or choose option", ::commit)
        bind("alt DOWN", "open options") { isPopupVisible = true }
        bind("F4", "toggle options") { isPopupVisible = !isPopupVisible }
        bind("ESCAPE", "dismiss options") {
            if (isPopupVisible) isPopupVisible = false
            else {
                val root = SwingUtilities.getRootPane(this)
                val action = root?.getInputMap(WHEN_IN_FOCUSED_WINDOW)?.get(KeyStroke.getKeyStroke("ESCAPE"))
                if (action != null) root.actionMap.get(action)?.actionPerformed(ActionEvent(root, ActionEvent.ACTION_PERFORMED, "ESCAPE"))
            }
        }
        bind("UP", "previous option") { move(-1) }
        bind("DOWN", "next option") { move(1) }
        bind("PAGE_UP", "options page up") { move(-maximumRowCount) }
        bind("PAGE_DOWN", "options page down") { move(maximumRowCount) }
        bind("HOME", "first option") { move(-itemCount) }
        bind("END", "last option") { move(itemCount) }
    }

    private class OptionsPopup(
        combo: JComboBox<Any>, private val theme: () -> CoverTheme, private val textWidth: () -> Int,
    ) : BasicComboPopup(combo) {
        init {
            setUI(BasicPopupMenuUI())
            isLightWeightPopupEnabled = true
            isOpaque = false
            background = sheetColor(theme())
            border = EmptyBorder(6, 6, 6, 6)
            list.setUI(BasicListUI())
            list.isOpaque = false
            list.border = null
            list.fixedCellHeight = 37
            list.foreground = TEXT
            list.font = font(13)
            list.cellRenderer = ListCellRenderer { _, value, index, highlighted, _ ->
                OptionRow(value.toString(), highlighted, index == combo.selectedIndex, theme)
            }
            styleScroll(scroller, theme)
            scroller.verticalScrollBar.unitIncrement = list.fixedCellHeight
        }

        override fun paintComponent(graphics: Graphics) {
            (graphics.create() as Graphics2D).useGraphics {
                it.quality()
                val surface = rounded(.5, .5, width - 1, height - 1, 15)
                it.color = sheetColor(theme())
                it.fill(surface)
                it.color = theme().accent.alpha(.45)
                it.draw(surface)
            }
        }

        override fun computePopupBounds(px: Int, py: Int, pw: Int, ph: Int): Rectangle {
            val config = comboBox.graphicsConfiguration
            val screen = Rectangle(config.bounds)
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
            screen.x += insets.left
            screen.y += insets.top
            screen.width -= insets.left + insets.right
            screen.height -= insets.top + insets.bottom
            val origin = comboBox.locationOnScreen
            val borders = border.getBorderInsets(this)
            val horizontal = borders.left + borders.right
            val vertical = borders.top + borders.bottom
            val width = maxOf(pw, (textWidth() + 59).coerceAtMost(480)).coerceAtMost((screen.width - horizontal).coerceAtLeast(1))
            val below = (screen.y + screen.height - origin.y - comboBox.height - vertical - 4).coerceAtLeast(0)
            val above = (origin.y - screen.y - vertical - 4).coerceAtLeast(0)
            val openBelow = below >= ph || below >= above
            val height = min(ph, if (openBelow) below else above).coerceAtLeast(1)
            val x = (origin.x + px + pw - width).coerceIn(screen.x, (screen.x + screen.width - width - horizontal).coerceAtLeast(screen.x))
            return Rectangle(x - origin.x, if (openBelow) py + 4 else -height - vertical - 4, width, height)
        }

        override fun show() {
            if (comboBox.itemCount == 0 || !comboBox.isShowing) return
            background = sheetColor(theme())
            list.background = background
            scroller.viewport.background = background
            super.show()
            comboBox.repaint()
        }

    }

    private class OptionRow(
        private val label: String, private val highlighted: Boolean, private val checked: Boolean,
        private val theme: () -> CoverTheme,
    ) : JLabel(label) {
        override fun getPreferredSize() = Dimension(120, 37)
        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            g.useGraphics {
                it.quality()
                val colors = theme()
                if (highlighted || checked) {
                    it.color = colors.accent.alpha(if (highlighted) .23 else .10)
                    it.fill(rounded(1, 1, width - 2, height - 2, 12))
                }
                it.color = TEXT
                it.font = font(13, checked)
                it.drawString(Stage.fit(it, label, width - 45), 10, (height + it.fontMetrics.ascent - it.fontMetrics.descent) / 2)
                if (checked) glyph(it, "check", width - 17.0, height * .5, 16.0, colors.accent)
            }
        }
    }
}

private fun iconButton(icon: String, name: String, theme: () -> CoverTheme) =
    PlayerButton("", icon, theme, size = 25, name = name, iconSize = 14.0, action = {}).apply {
        isFocusable = false
    }

internal fun sheetColor(theme: CoverTheme) = mix(theme.base, mix(theme.wells.first(), Color.BLACK, .72), .37)
    .let { mix(it, Color.WHITE, .06) }

internal fun pickFiles(owner: Window, title: String, extensions: Set<String> = emptySet(), multiple: Boolean = false,
                       suggestedName: String? = null): List<File> {
    val mode = if (suggestedName == null) FileDialog.LOAD else FileDialog.SAVE
    val picker = when (owner) {
        is Dialog -> FileDialog(owner, title, mode)
        is Frame -> FileDialog(owner, title, mode)
        else -> FileDialog(null as Frame?, title, mode)
    }
    try {
        picker.isMultipleMode = multiple
        picker.file = suggestedName
        if (extensions.isNotEmpty()) {
            picker.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
        }
        picker.isVisible = true
        return picker.files.filter { extensions.isEmpty() || it.extension.lowercase() in extensions }
    } finally {
        picker.dispose()
    }
}

internal class RoundedControlBorder(
    private val theme: () -> CoverTheme, private val insets: Insets, private val radius: Int = 13,
) : AbstractBorder() {
    override fun getBorderInsets(c: Component) = insets
    override fun getBorderInsets(c: Component, target: Insets): Insets = target.apply {
        set(insets.top, insets.left, insets.bottom, insets.right)
    }

    override fun paintBorder(c: Component, graphics: Graphics, x: Int, y: Int, w: Int, h: Int) {
        val g = graphics.create() as Graphics2D
        g.useGraphics {
            it.quality()
            val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            val active = c.hasFocus() || focus != null && c is Container && c.isAncestorOf(focus)
            it.color = theme().accent.alpha(if (active) .77 else .22)
            it.draw(rounded(x + .5, y + .5, w - 1, h - 1, radius))
        }
    }
}

internal fun numberControl(
    theme: () -> CoverTheme, initial: Int, low: Int, high: Int, step: Int, unit: String = "",
): JSpinner = JSpinner(SpinnerNumberModel(initial.coerceIn(low, high), low, high, step)).apply {
    setUI(object : BasicSpinnerUI() {
        override fun createNextButton(): Component = iconButton("up", "Increase", theme).also { installNextButtonListeners(it) }
        override fun createPreviousButton(): Component = iconButton("down", "Decrease", theme).also { installPreviousButtonListeners(it) }
    })
    preferredSize = Dimension(if (unit.isBlank()) 99 else 109, 36)
    isOpaque = false
    foreground = TEXT
    border = RoundedControlBorder(theme, Insets(2, 5, 2, 4))
    val spinner = this
    editor.isOpaque = false
    val numberEditor = editor as? JSpinner.NumberEditor
    numberEditor?.textField?.apply {
        setUI(BasicFormattedTextFieldUI())
        font = font(13, true)
        isOpaque = false
        foreground = TEXT
        caretColor = TEXT
        selectionColor = mix(sheetColor(theme()), theme().accent, .38)
        selectedTextColor = TEXT
        horizontalAlignment = SwingConstants.CENTER
        border = EmptyBorder(0, 3, 0, 3)
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = spinner.repaint()
            override fun focusLost(e: FocusEvent) = spinner.repaint()
        })
    }
    if (numberEditor != null && unit.isNotBlank()) {
        numberEditor.layout = BorderLayout()
        numberEditor.add(numberEditor.textField, BorderLayout.CENTER)
        numberEditor.add(textLabel(unit, 12, color = MUTED).apply { border = EmptyBorder(0, 0, 0, 3) }, BorderLayout.EAST)
    }
}

internal fun styleScroll(scroll: JScrollPane, theme: () -> CoverTheme): JScrollPane = scroll.apply {
    setUI(BasicScrollPaneUI())
    border = null
    viewportBorder = null
    isOpaque = false
    viewport.isOpaque = false
    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    verticalScrollBar.unitIncrement = 22
    verticalScrollBar.setUI(object : BasicScrollBarUI() {
        private fun emptyButton() = JButton().apply {
            preferredSize = Dimension(0, 0)
            minimumSize = preferredSize
            maximumSize = preferredSize
        }
        override fun createDecreaseButton(orientation: Int) = emptyButton()
        override fun createIncreaseButton(orientation: Int) = emptyButton()
        override fun getMinimumThumbSize() = Dimension(7, 28)
        override fun paintTrack(g: Graphics, c: JComponent, trackBounds: Rectangle) = Unit
        override fun paintThumb(graphics: Graphics, c: JComponent, bounds: Rectangle) {
            if (bounds.isEmpty) return
            val g = graphics.create() as Graphics2D
            g.useGraphics {
                it.quality()
                it.color = theme().accent.alpha(if (isThumbRollover) .59 else .32)
                it.fill(rounded(bounds.x + 3, bounds.y + 2, bounds.width - 6, bounds.height - 4, 7))
            }
        }
    })
    verticalScrollBar.isOpaque = false
    verticalScrollBar.preferredSize = Dimension(13, 0)
}

private fun styleForm(component: Component, theme: () -> CoverTheme) {
    when (component) {
        is JFormattedTextField -> Unit
        is JTextComponent -> {
            when (component) {
                is JPasswordField -> component.setUI(BasicPasswordFieldUI())
                is JTextField -> component.setUI(BasicTextFieldUI())
                is JTextArea -> component.setUI(BasicTextAreaUI())
            }
            component.font = font(13)
            component.foreground = TEXT
            component.background = sheetColor(theme())
            component.caretColor = TEXT
            component.selectionColor = mix(component.background, theme().accent, .32)
            component.selectedTextColor = TEXT
            component.isOpaque = component.isEditable
            component.border = if (component.isEditable) RoundedControlBorder(theme, Insets(8, 10, 8, 10))
                else EmptyBorder(2, 0, 2, 0)
            if (component.getClientProperty("formFocus") == null) {
                component.putClientProperty("formFocus", true)
                component.addFocusListener(object : FocusAdapter() {
                    override fun focusGained(e: FocusEvent) = component.repaint()
                    override fun focusLost(e: FocusEvent) = component.repaint()
                })
            }
        }
        is JScrollPane -> {
            styleScroll(component, theme)
            component.viewport.view?.let { styleForm(it, theme) }
        }
        is JList<*> -> {
            component.setUI(BasicListUI())
            component.background = sheetColor(theme())
            component.foreground = TEXT
            component.selectionBackground = mix(component.background, theme().accent, .23)
            component.selectionForeground = TEXT
            if (component.fixedCellHeight <= 0) component.fixedCellHeight = 32
            component.font = font(13)
            component.border = EmptyBorder(5, 8, 5, 8)
        }
        is JPanel -> {
            component.isOpaque = false
            component.components.forEach { styleForm(it, theme) }
        }
    }
}

internal class DialogSheets(private val theme: () -> CoverTheme) {
    fun choose(owner: Window, title: String, content: String, vararg options: String): Int {
        val text = JTextArea(content, 0, 43).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        return choose(owner, title, text, *options)
    }

    fun choose(owner: Window, title: String, content: JComponent, vararg options: String, primaryState: ButtonModel? = null): Int {
        val dialog = JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL).apply {
            isUndecorated = true
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        }
        val chrome = WindowChrome(dialog, theme, resizable = false)
        var answer = -1
        styleForm(content, theme)
        val label = textLabel(title, 18, true)
        val header = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            add(label, BorderLayout.CENTER)
            add(chrome.buttons, BorderLayout.EAST)
        }
        chrome.dragFrom(header, label)
        val choices = JPanel(FlowLayout(FlowLayout.RIGHT, 7, 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, theme().accent.alpha(.17)),
                EmptyBorder(8, 0, 0, 0),
            )
        }
        for (i in options.indices.reversed()) {
            choices.add(PlayerButton(options[i], "", theme, prominent = i == 0, size = 36) {
                answer = i
                dialog.dispose()
            }.also { button ->
                if (i == 0) {
                    dialog.rootPane.defaultButton = button
                    if (primaryState != null) {
                        button.isEnabled = primaryState.isEnabled
                        primaryState.addChangeListener { button.isEnabled = primaryState.isEnabled }
                    }
                }
            })
        }
        val shell = object : JPanel(BorderLayout(0, 15)) {
            val reveal = RevealMotion(this)
            override fun paintChildren(graphics: Graphics) = reveal.paint(graphics) { super.paintChildren(it) }
        }
        dialog.contentPane = shell.apply {
            background = sheetColor(theme())
            val line = RoundedControlBorder(theme, Insets(0, 0, 0, 0), 1)
            border = BorderFactory.createCompoundBorder(line, EmptyBorder(14, 18, 17, 17))
            add(header, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(content)
            }, BorderLayout.CENTER)
            if (options.isNotEmpty()) add(choices, BorderLayout.SOUTH)
        }
        dialog.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close")
        dialog.rootPane.actionMap.put("close", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = dialog.dispose()
        })
        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) {
                shell.reveal.enter()
                firstInput(content)?.requestFocusInWindow()
            }
        })
        dialog.pack()
        val screen = owner.graphicsConfiguration.bounds
        dialog.setSize(dialog.width.coerceIn(min(360, screen.width), screen.width),
            dialog.height.coerceAtMost((screen.height - 75).coerceAtLeast(260)))
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true
        return answer
    }

    fun confirm(owner: Window, title: String, content: JComponent, verb: String = "Apply") =
        choose(owner, title, content, verb, "Cancel") == 0

    fun confirm(owner: Window, title: String, content: String, verb: String = "Continue") =
        choose(owner, title, content, verb, "Cancel") == 0

    fun input(owner: Window, title: String, label: String, initial: String = ""): String? {
        val field = JTextField(initial, 33)
        val form = JPanel(BorderLayout(0, 8)).apply {
            isOpaque = false
            add(textLabel(label), BorderLayout.NORTH)
            add(field)
        }
        return if (confirm(owner, title, form)) field.text else null
    }

    fun folder(owner: Window, initial: File): File? {
        val path = JTextField(42)
        val directories = JList<File>().apply {
            visibleRowCount = 8
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean,
                ): Component = super.getListCellRendererComponent(list, value, index, selected, focus).also {
                    val file = value as? File
                    text = file?.name?.takeIf(String::isNotBlank) ?: file?.path.orEmpty()
                    border = EmptyBorder(0, 9, 0, 9)
                }
            }
        }
        var directory: File? = null
        fun show(file: File?) {
            val absolute = file?.absoluteFile
            val children = absolute?.let { runCatching(it::listFiles).getOrNull() }
            if (absolute != null && children == null) return
            directory = absolute
            path.text = absolute?.path.orEmpty()
            val choices = children?.filter(File::isDirectory)?.sortedBy { it.name.lowercase() }
                ?.toTypedArray() ?: File.listRoots()
            directories.setListData(choices)
        }
        fun openSelected() { directories.selectedValue?.let(::show) }
        path.addActionListener { path.text.trim().takeIf(String::isNotBlank)?.let(::File)?.let(::show) }
        directories.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) { if (event.clickCount == 2) openSelected() }
        })
        directories.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-folder")
        directories.actionMap.put("open-folder", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = openSelected()
        })
        show(initial.takeIf(File::isDirectory) ?: File(System.getProperty("user.home")))
        val controls = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            add(PlayerButton("", "up", theme, name = "Parent folder") { show(directory?.parentFile) }, BorderLayout.WEST)
            add(path, BorderLayout.CENTER)
        }
        val content = JPanel(BorderLayout(0, 10)).apply {
            isOpaque = false
            add(controls, BorderLayout.NORTH)
            add(JScrollPane(directories))
        }
        while (confirm(owner, "Choose lyrics folder", content, "Use this folder")) {
            val typed = path.text.trim().takeIf(String::isNotBlank)?.let(::File)
            val selected = if (typed?.absoluteFile == directory) directories.selectedValue ?: typed else typed
            if (selected?.isDirectory == true) return selected.absoluteFile
            info(owner, "Folder unavailable", "Choose an existing folder.")
        }
        return null
    }

    fun info(owner: Window, title: String, content: JComponent) { choose(owner, title, content, "Close") }
    fun info(owner: Window, title: String, content: String) { choose(owner, title, content, "Close") }

    private fun firstInput(component: Component): Component? = when (component) {
        is JComboBox<*>, is JSpinner, is JList<*> -> component
        is JTextComponent -> component.takeIf { it.isEditable }
        is Container -> component.components.firstNotNullOfOrNull(::firstInput)
        else -> null
    }
}
