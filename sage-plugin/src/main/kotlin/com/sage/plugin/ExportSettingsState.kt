package com.sage.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Persisted user preferences for the exporter: default export folder and
 * content toggles. Stored per-IDE install, application-wide (not
 * per-project), since chat sessions themselves aren't project-scoped either.
 */
@State(name = "CopilotChatExporterSettings", storages = [Storage("copilotChatExporter.xml")])
class ExportSettingsState : PersistentStateComponent<ExportSettingsState.State> {

    class State {
        /** Empty means "no default -- fall back to the user's home directory". */
        var defaultExportFolder: String = ""
        var includeThinkingBlocks: Boolean = true
        var includeRawToolJson: Boolean = true

        /** Default model name passed to `copilot --model` for the "analyse sessions" action; empty means let Copilot pick its own default. */
        var defaultLearningModel: String = ""
        /** Default target scopes for the "analyse sessions" action. */
        var defaultLearnProjectScope: Boolean = true
        var defaultLearnGlobalScope: Boolean = false
        /** Default learning mode for the "analyse sessions" action: "conservative" or "aggressive". */
        var defaultLearningMode: String = "conservative"

        /**
         * Cache of session file name -> first user prompt, used to label the
         * session picker dropdown without re-reading every session's data on
         * each open. Computing a session's first prompt can require a full
         * (and, for IDE sessions, potentially locked/slow) read, so entries
         * are computed once and reused thereafter.
         */
        var firstPromptCache: MutableMap<String, String> = mutableMapOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    companion object {
        fun getInstance(): ExportSettingsState =
            ApplicationManager.getApplication().getService(ExportSettingsState::class.java)
    }
}
