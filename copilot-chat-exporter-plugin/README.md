# Copilot Chat Exporter Plugin

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

## Usage

### From IDE

1. Open **Tools → Export Copilot Chat to Markdown**
2. If multiple sessions exist, select one from the picker
3. Choose save location (suggested name: `copilot-chat-<session-title>-<timestamp>.md`)
4. Click "Reveal in Explorer" to open the exported file

### Settings

Configure defaults under **Settings → Tools → Copilot Chat Exporter**:
- Default export folder
- Include thinking blocks
- Include raw tool JSON

### Supported Formats

- **IDE Session Export**: SQL Nitrite database format (.db files)
- **CLI Session Export**: JSONL event stream + YAML workspace config

## Architecture

### Module Structure

```
copilot-chat-exporter/
├── copilot-chat-reader/           # Core session reading logic
│   ├── CliSessionLocator.kt        # Discover CLI sessions
│   ├── CliSessionReader.kt         # Parse CLI JSONL events
│   ├── SessionLocator.kt           # Discover IDE plugin sessions
│   ├── NitriteSessionReader.kt     # Parse IDE Nitrite databases
│   └── MarkdownRenderer.kt         # Render to Markdown
│
└── copilot-chat-exporter-plugin/   # IntelliJ Plugin
    ├── plugin.xml                  # Plugin metadata + action registration
    ├── ExportCopilotChatAction.kt  # Main action implementation
    ├── ExportSettingsState.kt      # Persisted user preferences
    ├── ExportSettingsConfigurable.kt # Settings UI
    └── build.gradle.kts            # IntelliJ plugin build config
```

### Key Classes

**ExportCopilotChatAction** (plugin entry point)
- Extends `AnAction`
- Discovers available sessions (IDE + CLI)
- Shows session picker if multiple found
- Invokes file save dialog
- Renders selected session to Markdown
- Shows success notification with file location

**SessionPickerFilter**
- Pure filtering/sorting logic for the session picker
- Filters by title substring (case-insensitive) and sorts newest-first
- Unit-tested independently of Swing

## Building

### Prerequisites
- JDK 11+
- Gradle 8.0+

### Build Plugin

```bash
cd copilot-chat-exporter
gradle clean :copilot-chat-exporter-plugin:buildPlugin
```

Output: `copilot-chat-exporter-plugin/build/distributions/copilot-chat-exporter-*.zip`

### Development

Open the project in IntelliJ IDEA:
```bash
gradle openIdea
```

Run the plugin in a sandboxed IDE instance:
```bash
gradle :copilot-chat-exporter-plugin:runIde
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
3. From Tools menu → Export Copilot Chat to Markdown
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
- Nitrite: 4.4.2 (from copilot-chat-reader)

### Runtime
- IntelliJ Platform: 2024.2+
- GitHub Copilot Plugin: (optional, for IDE session detection)

## Version Notes

- **Plugin Version**: 0.1.0
- **Target IDE Version**: 2024.2 (Build 242+)
- **JVM Target**: Java 11
