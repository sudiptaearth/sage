# Sage Plugin

IntelliJ IDE plugin for exporting GitHub Copilot chat sessions to Markdown.

## Overview

This plugin provides a single-click export for both:
- **IDE Plugin Sessions**: Chat sessions from the Copilot panel in JetBrains IDEs
- **CLI Sessions**: Chat sessions from the GitHub Copilot CLI (`copilot-cli`)

## Features

✅ **Unified Export**: Single command works for both session types  
✅ **Rich Content**: Exports thinking blocks, tool calls, and results  
✅ **Auto-Detection**: Automatically discovers available sessions  
✅ **Session Picker**: Searchable, sortable dialog to choose from multiple sessions  
✅ **File Explorer Integration**: "Reveal in Explorer" button after export  
✅ **Configurable Settings**: Default export folder and content toggles (thinking blocks / raw tool JSON)  
✅ **Session Learning**: Multi-select sessions, analyse them with the `copilot` CLI for mistakes, and merge the lessons into a project and/or global instructions file

## Usage

### From IDE

1. Open **Tools → Summon Sage**
2. Choose **"Preserve the Holy Texts"** (export) or **"Seek Enlightenment"** (analyse & update learnings), or **"Dismiss"** to cancel

### Exporting a session ("Preserve the Holy Texts")

1. If multiple sessions exist, select one from the picker
2. Choose save location (suggested name: `copilot-chat-<session-title>-<timestamp>.md`)
3. Click "Reveal in Explorer" to open the exported file

### Analysing sessions and updating learnings ("Seek Enlightenment")

1. Select one or more sessions in the multi-select picker (Ctrl/Shift-click or drag)
2. In the "Direct the Sage's focus." dialog, choose which instructions file scope(s) to update -- project
   (`.github/copilot-instructions.md`), global
   (`~/.copilot/instructions/learnings.instructions.md`), or both -- and
   optionally a model name
3. The plugin shells out to the installed `copilot` CLI in the background
   (progress indicator shown); when finished, a notification shows a
   summary of the learnings Copilot added/merged

**Requires the `copilot` CLI to be installed and on `PATH`** (or the
`SAGE_CLI_PATH` environment variable set to its full path),
plus an active Copilot subscription/session -- this runs real agentic
reasoning per invocation, so expect it to take anywhere from several
seconds to under a minute depending on how many/how large the selected
sessions are.

## Requirements

- **An active GitHub Copilot subscription** (individual, business, or
  enterprise), signed in via the GitHub Copilot plugin and/or the `copilot`
  CLI. Sage doesn't call any Copilot/GitHub API directly for export -- it
  only reads chat data already saved locally -- but the "Seek Enlightenment"
  (analyse & update learnings) feature does invoke the `copilot` CLI, which
  requires a licensed, signed-in session to run.
- At least one prior Copilot chat session (IDE plugin or CLI) to export.
- For "Seek Enlightenment": the `copilot` CLI installed and on `PATH` (or
  `SAGE_CLI_PATH` set to its full path).
- IntelliJ Platform 2024.2+ (Build 242+), JDK 11+ if building from source.
- The GitHub Copilot IDE plugin is optional -- only needed if you want to
  export/analyse IDE-panel sessions rather than CLI-only sessions.


### Settings

Configure defaults under **Settings → Tools → Sage**:
- Default export folder
- Include thinking blocks
- Include raw tool JSON
- Default learning target scope(s) and model (remembered from the last
  "Analyse Copilot Sessions" run)

### Supported Formats

- **IDE Session Export**: SQL Nitrite database format (.db files)
- **CLI Session Export**: JSONL event stream + YAML workspace config

## Architecture

### Module Structure

```
sage/
├── sage-reader/           # Core session reading logic
│   ├── CliSessionLocator.kt        # Discover CLI sessions
│   ├── CliSessionReader.kt         # Parse CLI JSONL events
│   ├── SessionLocator.kt           # Discover IDE plugin sessions
│   ├── NitriteSessionReader.kt     # Parse IDE Nitrite databases
│   ├── MarkdownRenderer.kt         # Render to Markdown
│   └── learn/                      # Session analysis & instructions-file generation
│       ├── CopilotCliInvoker.kt     # Shells out to the `copilot` CLI
│       ├── LearningAnalyzer.kt      # Orchestrates analyse/merge/write
│       └── InstructionsFileTargets.kt # Resolves project/global file paths
│
└── sage-plugin/   # IntelliJ Plugin
    ├── plugin.xml                  # Plugin metadata + action registration
    ├── ExportCopilotChatAction.kt  # Export action + single-select picker
    ├── AnalyzeSessionsAction.kt    # "Analyse Sessions & Update Learnings" action
    ├── SessionDiscovery.kt         # Shared session discovery/model (reflective reader access)
    ├── MultiSessionPickerDialog.kt # Multi-select session picker
    ├── LearningOptionsDialog.kt    # Target scope + model picker
    ├── ExportSettingsState.kt      # Persisted user preferences
    ├── ExportSettingsConfigurable.kt # Settings UI
    └── build.gradle.kts            # IntelliJ plugin build config
```

### Key Classes

**ExportCopilotChatAction** (export entry point)
- Extends `AnAction`
- Discovers available sessions (IDE + CLI) via `SessionDiscovery`
- Shows single-select session picker if multiple found
- Invokes file save dialog
- Renders selected session to Markdown
- Shows success notification with file location

**AnalyzeSessionsAction** (learning entry point)
- Extends `AnAction`
- Discovers sessions via `SessionDiscovery`, shows `MultiSessionPickerDialog`
  (multi-select) then `LearningOptionsDialog` (target scope + model)
- Reads raw `ChatSession` objects and invokes
  `com.sage.reader.learn.LearningAnalyzer` reflectively (same
  reflection pattern as every other reader-module integration here) on a
  background task, showing a notification with Copilot's summary when done

**SessionDiscovery**
- Shared IDE/CLI session discovery, first-prompt caching, and raw
  `ChatSession` reading, extracted so both actions reuse the exact same
  reflective reader-module glue

**SessionPickerFilter**
- Pure filtering/sorting logic for the session picker(s)
- Filters by title substring (case-insensitive) and sorts newest-first
- Unit-tested independently of Swing

## Building

### Prerequisites
- JDK 11+
- Gradle 8.0+

### Build Plugin

```bash
cd sage
gradle clean :sage-plugin:buildPlugin
```

Output: `sage-plugin/build/distributions/sage-*.zip`

### Development

Open the project in IntelliJ IDEA:
```bash
gradle openIdea
```

Run the plugin in a sandboxed IDE instance:
```bash
gradle :sage-plugin:runIde
```

## Troubleshooting

### "No Copilot chat sessions found"
- Ensure you have run at least one chat in the IDE or CLI
- Check that `~/.copilot/session-state/` exists (CLI) or IDE database is accessible
- For IDE: Close any active debug sessions that might lock the database

### File locked error
- The IDE plugin database is locked when an IDE window with Copilot chat is open
- Close all IDE windows with active Copilot chat, then retry

### Export looks incomplete
- Older sessions may not have thinking blocks or tool calls
- Ensure you're exporting a recent chat session
- Check that CliSessionReader correctly parsed all events

## Known Limitations

1. **File Locking**: Cannot export sessions from an actively-open IDE (Windows file lock)
   - Workaround: Close the IDE window first

2. **Schema Volatility**: Both IDE (.db) and CLI (JSONL) formats are undocumented
   - If a future Copilot version changes format, compatibility may break

## Testing

### Manual E2E Test

1. Open IntelliJ with a project
2. Create a Copilot chat in the IDE
3. From Tools menu → Summon Sage → Preserve the Holy Texts
4. Verify:
   - ✅ Session appears in picker
   - ✅ File save dialog shows suggested filename
   - ✅ Markdown exports correctly
   - ✅ "Reveal in Explorer" opens file location

### Test Coverage

- [x] CLI session discovery
- [x] CLI event parsing (user, assistant, tool calls, thinking)
- [x] Markdown rendering with rich content
- [x] File save and notification
- [x] IDE session discovery
- [x] IDE Nitrite parsing
- [x] Multi-session picker UI
- [x] Session picker filter/sort logic

## Dependencies

### Build-Time
- Gradle IntelliJ Plugin: 1.17.0
- Kotlin: 1.9.22
- Nitrite: 4.4.2 (from sage-reader)

### Runtime
- IntelliJ Platform: 2024.2+
- GitHub Copilot Plugin: (optional, for IDE session detection)

## Version Notes

- **Plugin Version**: 0.1.0
- **Target IDE Version**: 2024.2 (Build 242+)
- **JVM Target**: Java 11
