package com.sage.plugin

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * Multi-select variant of [SessionPickerDialog] (which stays single-select
 * for the plain "export one session" action). Reuses [SessionPickerFilter]
 * for search/sort and [SessionListCellRenderer] for row rendering, so the
 * two pickers stay visually/behaviourally consistent.
 */
internal class MultiSessionPickerDialog(
    private val allSessions: List<SessionInfo>
) : DialogWrapper(true) {

    private val listModel = CollectionListModel<SessionInfo>()
    private val list = JBList(listModel)
    private val searchField = JBTextField()

    var selectedSessions: List<SessionInfo> = emptyList()
        private set

    init {
        title = "Select Copilot Chat Sessions to Analyse"
        setOKButtonText("Analyse")
        list.selectionMode = javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        list.cellRenderer = SessionListCellRenderer()
        refreshList()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(560, 420)

        val topPanel = JPanel(BorderLayout(8, 0))
        topPanel.add(JLabel("Search:"), BorderLayout.WEST)
        topPanel.add(searchField, BorderLayout.CENTER)
        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)

        val hint = JLabel("Ctrl/Shift-click or drag to select multiple sessions.")
        panel.add(hint, BorderLayout.SOUTH)

        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refreshList()
        })

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = searchField

    private fun refreshList() {
        val query = searchField.text.orEmpty()
        val filtered = SessionPickerFilter.apply(allSessions, query)
        listModel.replaceAll(filtered)
        if (filtered.isNotEmpty() && list.selectedIndices.isEmpty()) {
            list.selectedIndex = 0
        }
    }

    override fun doOKAction() {
        selectedSessions = list.selectedValuesList
        super.doOKAction()
    }
}
