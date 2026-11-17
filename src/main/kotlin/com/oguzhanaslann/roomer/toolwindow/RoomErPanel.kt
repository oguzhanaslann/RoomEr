package com.oguzhanaslann.roomer.toolwindow

import com.intellij.ide.ui.LafManagerListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.oguzhanaslann.roomer.RoomErBundle
import com.oguzhanaslann.roomer.RoomErNotifications
import com.oguzhanaslann.roomer.discovery.DiscoveredDatabase
import com.oguzhanaslann.roomer.discovery.SchemaWatcher
import com.oguzhanaslann.roomer.layout.Point
import com.oguzhanaslann.roomer.model.SchemaError
import com.oguzhanaslann.roomer.navigation.EntityNavigator
import com.oguzhanaslann.roomer.render.DiagramCanvas
import com.oguzhanaslann.roomer.render.DiagramCanvasListener
import com.oguzhanaslann.roomer.search.DiagramSearch
import com.oguzhanaslann.roomer.search.SearchResult
import com.oguzhanaslann.roomer.state.RoomErState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.IOException
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * Content of the "Room ER" tool window: header (database, version, search, toolbar), the diagram canvas
 * and the empty/error states. All methods are EDT-only; loading happens in [controller] off the EDT.
 *
 * @param scope cancelled when the panel is disposed
 */
class RoomErPanel(
    private val project: Project,
    private val controller: DiagramController,
    private val scope: CoroutineScope,
) : JPanel(BorderLayout()), UiDataProvider, Disposable {

    val canvas = DiagramCanvas()
    private val navigator = EntityNavigator(project)

    // `internal` members below are read by RoomErPanelTest; nothing outside this class changes them.
    private val databaseModel = DefaultComboBoxModel<DiscoveredDatabase>()
    internal val databaseCombo = ComboBox(databaseModel)
    internal val titleLabel = JBLabel()
    internal val versionLabel = JBLabel()
    internal val searchField = SearchTextField(false)
    internal val resultsModel = CollectionListModel<SearchResult>()
    internal val resultsList = JBList(resultsModel)
    internal val resultsPane = ScrollPaneFactory.createScrollPane(resultsList, true)

    internal val errorBanner = EditorNotificationPanel(EditorNotificationPanel.Status.Error)
    private val loadingPanel = JBLoadingPanel(BorderLayout(), this, 0)
    internal val messagePanel = MessagePanel()
    private val cards = CardLayout()
    private val cardPanel = JPanel(cards)

    private var updatingCombo = false
    private var shownModelRevision = -1L
    /** The snapshot currently rendered (EDT only). */
    internal var shownSnapshot = DiagramSnapshot()
        private set
    /** Name of the visible card: [CARD_DIAGRAM], [CARD_MESSAGE] or [CARD_BLANK]. */
    internal var visibleCard = CARD_BLANK
        private set
    private var loadJob: Job? = null

    /**
     * Asks where to save the Mermaid file; null = cancelled. Replaceable so tests can stand in for the
     * modal save dialog (AC-20.2).
     */
    internal var mermaidTargetChooser: (defaultFileName: String) -> Path? = ::askMermaidTarget

    private val watcher: SchemaWatcher

    init {
        add(createHeader(), BorderLayout.NORTH)

        val diagramCard = JPanel(BorderLayout())
        errorBanner.isVisible = false
        diagramCard.add(errorBanner, BorderLayout.NORTH)
        diagramCard.add(canvas, BorderLayout.CENTER)
        cardPanel.add(diagramCard, CARD_DIAGRAM)
        cardPanel.add(messagePanel.component, CARD_MESSAGE)
        cardPanel.add(JPanel(), CARD_BLANK)
        showCard(CARD_BLANK)
        loadingPanel.add(cardPanel, BorderLayout.CENTER)
        loadingPanel.setLoadingText(RoomErBundle.message("loading.text"))
        add(loadingPanel, BorderLayout.CENTER)

        installSearch()
        installCanvasListener()
        canvas.legendVisible = RoomErState.getInstance(project).legendVisible

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener {
                // The canvas takes its colours from UIManager; make it re-read them (AC-13.1).
                canvas.updateUI()
                canvas.revalidate()
                canvas.repaint()
            })

        watcher = SchemaWatcher(
            project = project,
            parent = this,
            scope = scope,
            isRelevant = controller::isWatchedPath,
            onChange = { controller.reload(force = false) },
        )

        scope.launch(Dispatchers.EDT) {
            controller.snapshots.collect { render(it) }
        }
        scope.launch(Dispatchers.EDT) {
            // NFR-01 / AC-21.4: the indicator appears only when a load takes longer than 300 ms.
            controller.loading.collectLatest { loading ->
                if (loading) {
                    delay(LOADING_DELAY_MILLIS)
                    loadingPanel.startLoading()
                } else {
                    loadingPanel.stopLoading()
                }
            }
        }
        refresh()
    }

    // ---- actions (called by RoomErActions) ---------------------------------------------------------

    val hasDiagram: Boolean get() = shownSnapshot.state is DiagramState.Diagram
    val hasSelection: Boolean get() = hasDiagram && canvas.selectedEntity != null

    var focusMode: Boolean
        get() = hasDiagram && canvas.focusMode
        set(value) {
            if (hasDiagram) canvas.focusMode = value
        }

    fun refresh() = launchLoad { controller.reload(force = true) }

    fun resetLayout() = launchLoad { controller.resetLayout() }

    // The canvas reports programmatic collapse changes through DiagramCanvasListener.collapsedChanged as well
    // (never for a no-op), so the listener persists them; calling the controller here would persist twice.
    fun collapseAll() {
        if (hasDiagram) canvas.collapseAll()
    }

    fun expandAll() {
        if (hasDiagram) canvas.expandAll()
    }

    /** The legend overlay (toolbar toggle); remembered per project in [RoomErState.legendVisible]. */
    var legendVisible: Boolean
        get() = canvas.legendVisible
        set(value) {
            canvas.legendVisible = value
            RoomErState.getInstance(project).legendVisible = value
        }

    fun copyAsMermaid() {
        val text = controller.mermaidText() ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        RoomErNotifications.notify(project, RoomErBundle.message("mermaid.copied"), NotificationType.INFORMATION)
    }

    fun saveAsMermaid() {
        val diagram = shownSnapshot.state as? DiagramState.Diagram ?: return
        val text = controller.mermaidText() ?: return
        val path = mermaidTargetChooser(MermaidExport.defaultFileName(diagram.database))
            ?: return // cancelled: nothing is written (AC-20.2)
        scope.launch(Dispatchers.IO) {
            try {
                MermaidExport.write(path, text)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                RoomErNotifications.notify(project, RoomErBundle.message("mermaid.saved", path.toString()), NotificationType.INFORMATION)
            } catch (e: IOException) {
                RoomErNotifications.notify(
                    project,
                    RoomErBundle.message("mermaid.save.failed", path.toString(), e.message ?: e.javaClass.simpleName),
                    NotificationType.ERROR,
                )
            }
        }
    }

    private fun askMermaidTarget(defaultFileName: String): Path? {
        val descriptor = FileSaverDescriptor(
            RoomErBundle.message("mermaid.save.title"),
            RoomErBundle.message("mermaid.save.description"),
            MermaidExport.EXTENSION,
        )
        return FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(project.guessProjectDir(), defaultFileName)
            ?.file?.toPath()
    }

    fun chooseSchemaFolder() {
        val descriptor = FileChooserDescriptorFactory.singleDir()
            .withTitle(RoomErBundle.message("choose.folder.title"))
            .withDescription(RoomErBundle.message("choose.folder.description"))
        val current = RoomErState.getInstance(project).schemaFolderUrl?.let { VirtualFileManager.getInstance().findFileByUrl(it) }
        val folder = FileChooser.chooseFile(descriptor, this, project, current ?: project.guessProjectDir()) ?: return
        launchLoad {
            val result = controller.chooseSchemaFolder(folder.url)
            // FR-42 when other databases exist: the diagram stays, so say it in a notification instead.
            result.emptyManualFolder?.takeIf { result.databases.isNotEmpty() }?.let {
                RoomErNotifications.notify(project, RoomErBundle.message("state.folder.empty.title", it), NotificationType.WARNING)
            }
        }
    }

    fun useAutomaticDiscovery() = launchLoad { controller.chooseSchemaFolder(null) }

    fun focusSearch() {
        searchField.requestFocusInWindow()
        searchField.selectText()
    }

    /** Starts a load; a load requested earlier by this panel that is still running is superseded. */
    private fun launchLoad(block: suspend () -> Unit) {
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Loading Room schemas failed", e)
            }
        }
    }

    // ---- rendering of controller state -------------------------------------------------------------

    private fun render(snapshot: DiagramSnapshot) {
        shownSnapshot = snapshot
        updateDatabaseCombo(snapshot)
        when (val state = snapshot.state) {
            DiagramState.Loading -> {
                setHeader(null, null, null)
                showCard(CARD_BLANK)
            }

            DiagramState.NoSchemas -> {
                clearCanvas()
                setHeader(null, null, null)
                messagePanel.showNoSchemas(onChooseFolder = ::chooseSchemaFolder)
                showCard(CARD_MESSAGE)
            }

            is DiagramState.FolderWithoutSchemas -> {
                clearCanvas()
                setHeader(null, null, null)
                messagePanel.showEmptyFolder(state.folderPath, ::chooseSchemaFolder, ::useAutomaticDiscovery)
                showCard(CARD_MESSAGE)
            }

            is DiagramState.Failed -> {
                clearCanvas()
                setHeader(state.source.label, null, state.source.name)
                messagePanel.showError(state.error, onOpenFile = { openFile(state.error.filePath) })
                showCard(CARD_MESSAGE)
            }

            is DiagramState.Diagram -> {
                if (state.modelRevision != shownModelRevision) {
                    shownModelRevision = state.modelRevision
                    canvas.setDiagram(state.database, state.positions, state.collapsed, state.preserveViewport)
                    updateSearchResults()
                }
                if (canvas.stale != state.stale) canvas.stale = state.stale
                // AC-04.1: name and version of the diagram on screen — for a stale diagram, the last good one.
                setHeader(state.database.simpleName, state.database.version, state.database.name)
                showBanner(state)
                showCard(CARD_DIAGRAM)
            }
        }
    }

    private fun showCard(name: String) {
        visibleCard = name
        cards.show(cardPanel, name)
    }

    private fun clearCanvas() {
        if (shownModelRevision != -1L) {
            shownModelRevision = -1L
            canvas.clearDiagram()
        }
        errorBanner.isVisible = false
        searchField.text = ""
    }

    private fun showBanner(state: DiagramState.Diagram) {
        val error = state.error
        if (error == null) {
            errorBanner.isVisible = false
            return
        }
        // clear() also empties the text, so it must come first.
        errorBanner.clear()
        errorBanner.text = RoomErBundle.message("banner.stale", state.database.version, error.filePath, describe(error))
        errorBanner.toolTipText = error.filePath
        errorBanner.createActionLabel(RoomErBundle.message("link.open.file")) { openFile(error.filePath) }
        errorBanner.isVisible = true
        errorBanner.revalidate()
    }

    private fun setHeader(name: String?, version: Int?, fullName: String?) {
        titleLabel.text = name.orEmpty()
        titleLabel.toolTipText = fullName
        versionLabel.text = version?.let { RoomErBundle.message("header.version", it) }.orEmpty()
        versionLabel.toolTipText = (shownSnapshot.state as? DiagramState.Diagram)?.database?.sourcePath
    }

    private fun updateDatabaseCombo(snapshot: DiagramSnapshot) {
        updatingCombo = true
        try {
            val current = (0 until databaseModel.size).map { databaseModel.getElementAt(it) }
            if (current != snapshot.databases) {
                databaseModel.removeAllElements()
                snapshot.databases.forEach(databaseModel::addElement)
            }
            databaseModel.selectedItem = snapshot.selected
            // AC-03.3: a single database needs no chooser; the title label names it.
            databaseCombo.isVisible = snapshot.databases.size > 1
            titleLabel.isVisible = snapshot.databases.size <= 1
            databaseCombo.toolTipText = snapshot.selected?.name
        } finally {
            updatingCombo = false
        }
    }

    private fun openFile(path: String) {
        val file = controller.findFile(path) ?: LocalFileSystem.getInstance().findFileByPath(path) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    // ---- header ------------------------------------------------------------------------------------

    private fun createHeader(): JComponent {
        databaseCombo.renderer = DatabaseRenderer()
        databaseCombo.isVisible = false
        databaseCombo.addActionListener {
            val chosen = databaseCombo.selectedItem as? DiscoveredDatabase
            if (!updatingCombo && chosen != null && chosen.id != shownSnapshot.selected?.id) {
                launchLoad { controller.selectDatabase(chosen.id) }
            }
        }
        titleLabel.font = JBUI.Fonts.label().asBold()
        versionLabel.foreground = UIUtil.getContextHelpForeground()

        val identity = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 8)
            add(databaseCombo)
            add(titleLabel)
            add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
            add(versionLabel)
        }

        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLWINDOW_CONTENT,
            ActionManager.getInstance().getAction(TOOLBAR_GROUP_ID) as ActionGroup,
            true,
        )
        toolbar.targetComponent = this

        searchField.textEditor.emptyText.text = RoomErBundle.message("search.placeholder")
        searchField.textEditor.columns = 14

        val firstRow = JPanel(BorderLayout()).apply {
            add(identity, BorderLayout.WEST)
            add(toolbar.component, BorderLayout.CENTER)
            add(searchField, BorderLayout.EAST)
        }
        resultsPane.isVisible = false
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.borderColor())
            add(firstRow, BorderLayout.NORTH)
            add(resultsPane, BorderLayout.CENTER)
        }
    }

    // ---- search (FR-30, AC-14.x) -------------------------------------------------------------------

    private fun installSearch() {
        resultsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resultsList.visibleRowCount = 6
        resultsList.emptyText.text = RoomErBundle.message("search.no.results")
        resultsList.cellRenderer = SearchResultRenderer()
        resultsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = resultsList.locationToIndex(e.point)
                if (index >= 0 && resultsList.getCellBounds(index, index)?.contains(e.point) == true) {
                    resultsList.selectedIndex = index
                    showResult(resultsModel.getElementAt(index))
                }
            }
        })
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateSearchResults()
        })
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> moveResultSelection(1)
                    KeyEvent.VK_UP -> moveResultSelection(-1)
                    KeyEvent.VK_ENTER -> resultsList.selectedValue?.let(::showResult)
                    KeyEvent.VK_ESCAPE -> {
                        searchField.text = ""
                        canvas.requestFocusInWindow()
                    }
                    else -> return
                }
                e.consume()
            }
        })
    }

    private fun updateSearchResults() {
        val query = searchField.text.orEmpty()
        val database = (shownSnapshot.state as? DiagramState.Diagram)?.database
        val searching = query.isNotBlank() && database != null
        val results = if (database != null && query.isNotBlank()) DiagramSearch.search(database, query) else emptyList()
        resultsModel.replaceAll(results)
        if (results.isNotEmpty()) resultsList.selectedIndex = 0
        if (resultsPane.isVisible != searching) {
            resultsPane.isVisible = searching
            revalidate()
            repaint()
        }
    }

    private fun moveResultSelection(delta: Int) {
        if (resultsModel.isEmpty) return
        val index = (resultsList.selectedIndex + delta).coerceIn(0, resultsModel.size - 1)
        resultsList.selectedIndex = index
        resultsList.ensureIndexIsVisible(index)
    }

    private fun showResult(result: SearchResult) {
        if (hasDiagram) canvas.select(result.entity, result.column, true)
    }

    // ---- canvas callbacks --------------------------------------------------------------------------

    private fun installCanvasListener() {
        canvas.addCanvasListener(object : DiagramCanvasListener {
            override fun goToSourceRequested(entityName: String) = goToSource(entityName)
            override fun positionsChanged(positions: Map<String, Point>) = controller.positionsChanged(positions)
            override fun collapsedChanged(collapsed: Set<String>) = controller.collapsedChanged(collapsed)
            override fun searchRequested() = focusSearch()
            override fun escapePressed() {
                searchField.text = ""
            }
            // collapsedChanged also fires for programmatic changes (collapseAll/expandAll, select() expanding a
            // table), never for no-ops, and setDiagram never fires it. The controller only updates its snapshot
            // without bumping modelRevision, so render() does not call back into the canvas: no loop.
            // selectionChanged / focusModeChanged are not needed: the toolbar reads them from the canvas on update.
        })
    }

    private fun goToSource(entityName: String) {
        val diagram = shownSnapshot.state as? DiagramState.Diagram ?: return
        val entity = diagram.database.entity(entityName) ?: return
        val schemaFile = diagram.database.sourcePath?.let(controller::findFile)
            ?: controller.findFile(diagram.source.latestFilePath)
        scope.launch {
            try {
                navigator.goToSource(entityName, entity.kind, schemaFile, canvas)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Go to source failed for $entityName", e)
            }
        }
    }

    private fun describe(error: SchemaError): String = MessagePanel.describe(error)

    override fun uiDataSnapshot(sink: DataSink) {
        sink[KEY] = this
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        val KEY: DataKey<RoomErPanel> = DataKey.create("RoomEr.Panel")
        const val TOOLBAR_GROUP_ID: String = "RoomEr.Toolbar"
        private const val LOADING_DELAY_MILLIS = 300L
        internal const val CARD_DIAGRAM = "diagram"
        internal const val CARD_MESSAGE = "message"
        internal const val CARD_BLANK = "blank"
        private val LOG = com.intellij.openapi.diagnostic.logger<RoomErPanel>()
    }
}

// Plain SimpleListCellRenderer subclasses rather than the ui.dsl listCellRenderer builders: those are
// @ApiStatus.Internal in Android Studio 2026.1 (261), and the plugin verifier rejects internal API usage.

/** Database dropdown: simple class name, full name as tooltip (AC-03.1). */
private class DatabaseRenderer : SimpleListCellRenderer<DiscoveredDatabase>() {
    override fun customize(list: JList<out DiscoveredDatabase>, value: DiscoveredDatabase?, index: Int, selected: Boolean, hasFocus: Boolean) {
        text = value?.label ?: ""
        toolTipText = value?.name
    }
}

/** Search results: `table` or `table.column`. */
private class SearchResultRenderer : SimpleListCellRenderer<SearchResult>() {
    override fun customize(list: JList<out SearchResult>, value: SearchResult?, index: Int, selected: Boolean, hasFocus: Boolean) {
        text = when {
            value == null -> ""
            value.column == null -> value.entity
            else -> "${value.entity}.${value.column}"
        }
    }
}
