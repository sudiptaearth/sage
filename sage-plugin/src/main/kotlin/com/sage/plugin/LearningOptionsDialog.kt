package com.sage.plugin

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Small dialog shown after session selection: which instructions file
 * scope(s) to update (project and/or global), and an optional model name
 * passed straight through as `copilot --model`.
 */
internal class LearningOptionsDialog(
    private val hasProject: Boolean,
    defaultProjectScope: Boolean,
    defaultGlobalScope: Boolean,
    defaultModel: String,
    private val repoRoot: Path? = null
) : DialogWrapper(true) {

    private val projectCheckBox = JBCheckBox("Project (.github/copilot-instructions.md)", defaultProjectScope && hasProject)
    private val globalCheckBox = JBCheckBox("Global (~/.copilot/instructions/learnings.instructions.md)", defaultGlobalScope)
    private val modelField = JBTextField(defaultModel)
    private val previewArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    var selectProject: Boolean = false
        private set
    var selectGlobal: Boolean = false
        private set
    var model: String? = null
        private set

    init {
        title = "Analyse Copilot Sessions"
        setOKButtonText("Analyse")
        projectCheckBox.isEnabled = hasProject
        init()
        refreshPreview()
    }

    override fun createCenterPanel(): JComponent {
        val topPanel = JPanel(GridLayout(0, 1, 4, 8))
        topPanel.add(JBLabel("Update which instructions file(s) with the learnings?"))
        topPanel.add(projectCheckBox)
        topPanel.add(globalCheckBox)
        if (!hasProject) {
            topPanel.add(JBLabel("(Project scope unavailable -- no project is open.)"))
        }
        topPanel.add(JBLabel("Model (optional, blank = Copilot's default):"))
        topPanel.add(modelField)
        topPanel.add(JBLabel("Examples: claude-sonnet-5, claude-opus-4.8, gpt-5.5, gemini-3.1-pro-preview"))

        val previewPanel = JPanel(BorderLayout(0, 4))
        previewPanel.add(JBLabel("Current content of the selected instructions file(s):"), BorderLayout.NORTH)
        val scrollPane = JBScrollPane(previewArea)
        scrollPane.preferredSize = Dimension(520, 220)
        previewPanel.add(scrollPane, BorderLayout.CENTER)

        val panel = JPanel(BorderLayout(0, 8))
        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(previewPanel, BorderLayout.CENTER)

        val refreshListener = { refreshPreview() }
        projectCheckBox.addActionListener { refreshListener() }
        globalCheckBox.addActionListener { refreshListener() }

        return panel
    }

    /** Reloads [previewArea] with the on-disk content of whichever scope(s) are currently checked. */
    private fun refreshPreview() {
        val sections = mutableListOf<String>()
        if (projectCheckBox.isSelected) {
            val path = repoRoot?.resolve(".github")?.resolve("copilot-instructions.md")
            sections += renderSection("Project", path)
        }
        if (globalCheckBox.isSelected) {
            val path = Paths.get(System.getProperty("user.home"), ".copilot", "instructions", "learnings.instructions.md")
            sections += renderSection("Global", path)
        }
        previewArea.text = if (sections.isEmpty()) {
            "(No scope selected.)"
        } else {
            sections.joinToString("\n\n---\n\n")
        }
        previewArea.caretPosition = 0
    }

    private fun renderSection(label: String, path: Path?): String {
        if (path == null) {
            return "[$label]\n(Path unavailable -- no project is open.)"
        }
        val content = if (Files.isRegularFile(path)) {
            runCatching { Files.readString(path) }.getOrElse { "(Could not read file: ${it.message})" }
        } else {
            "(File does not exist yet: $path)"
        }
        return "[$label] $path\n$content"
    }

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? {
        if (!projectCheckBox.isSelected && !globalCheckBox.isSelected) {
            return com.intellij.openapi.ui.ValidationInfo("Select at least one target scope.", projectCheckBox)
        }
        return null
    }

    override fun doOKAction() {
        selectProject = projectCheckBox.isSelected
        selectGlobal = globalCheckBox.isSelected
        model = modelField.text.trim().takeIf { it.isNotEmpty() }
        super.doOKAction()
    }
}
