package com.copilotexport.plugin

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → Copilot Chat Exporter: default export folder + content
 * toggles, so users don't have to re-pick a folder and re-decide on
 * verbosity every single export.
 */
class ExportSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private val folderField = TextFieldWithBrowseButton()
    private val includeThinkingCheckBox = JBCheckBox("Include thinking/reasoning blocks")
    private val includeRawToolJsonCheckBox = JBCheckBox("Include raw tool call JSON (uncheck for a one-line summary per tool call)")

    override fun getDisplayName(): String = "Copilot Chat Exporter"

    override fun createComponent(): JComponent {
        folderField.addBrowseFolderListener(
            "Default Export Folder",
            "Where exported Markdown files are saved by default (leave blank to use your home directory)",
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )

        val built = FormBuilder.createFormBuilder()
            .addLabeledComponent("Default export folder:", folderField)
            .addComponent(includeThinkingCheckBox)
            .addComponent(includeRawToolJsonCheckBox)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel = built
        return built
    }

    override fun isModified(): Boolean {
        val state = ExportSettingsState.getInstance().state
        return folderField.text != state.defaultExportFolder ||
            includeThinkingCheckBox.isSelected != state.includeThinkingBlocks ||
            includeRawToolJsonCheckBox.isSelected != state.includeRawToolJson
    }

    override fun apply() {
        val state = ExportSettingsState.getInstance().state
        state.defaultExportFolder = folderField.text.trim()
        state.includeThinkingBlocks = includeThinkingCheckBox.isSelected
        state.includeRawToolJson = includeRawToolJsonCheckBox.isSelected
    }

    override fun reset() {
        val state = ExportSettingsState.getInstance().state
        folderField.text = state.defaultExportFolder
        includeThinkingCheckBox.isSelected = state.includeThinkingBlocks
        includeRawToolJsonCheckBox.isSelected = state.includeRawToolJson
    }

    override fun disposeUIResources() {
        panel = null
    }
}
