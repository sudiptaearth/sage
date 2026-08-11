package com.sage.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

/**
 * Single toolbar entry point replacing the previous two separate actions
 * ("Export Copilot Chat to Markdown" and "Analyse Copilot Sessions & Update
 * Learnings"). Asks the user which mode they want first, then delegates
 * unchanged to the existing action's [AnAction.actionPerformed] -- the export
 * and analysis flows themselves are untouched, only the single entry point
 * merges here.
 */
class CopilotChatAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val choice = Messages.showDialog(
            event.project,
            "Preserve the Holy Texts will export your Copilot chat sessions to Markdown for safekeeping, while Seek Enlightenment will analyse those sessions to extract lessons and update your project's Copilot instructions.",
            "Direct the Sage's focus.",
            arrayOf("Preserve the Holy Texts", "Seek Enlightenment", "Dismiss"),
            0,
            Messages.getQuestionIcon()
        )
        when (choice) {
            0 -> ExportCopilotChatAction().actionPerformed(event)
            1 -> AnalyzeSessionsAction().actionPerformed(event)
            else -> Unit // Cancel or dialog dismissed
        }
    }
}
