# Copilot Chat Exporter

A multi-module Gradle project for exporting GitHub Copilot chat sessions to Markdown, from either the JetBrains IDE plugin or the GitHub Copilot CLI.

## Modules

- **[`copilot-chat-reader`](copilot-chat-reader/README.md)** -- Plain Kotlin library (no IntelliJ dependency) that discovers, parses, and renders Copilot chat sessions (IDE Nitrite `.db` files and CLI JSONL sessions) to Markdown. Includes a CLI harness for standalone use and testing.
- **[`copilot-chat-exporter-plugin`](copilot-chat-exporter-plugin/README.md)** -- IntelliJ IDE plugin that wraps the reader in a one-click **Tools → Export Copilot Chat to Markdown** action, with a session picker, settings page, and file save/reveal integration.

## Building

Requires JDK 11+. The project uses the Gradle wrapper, so no separate Gradle install is needed.

```bash
# From the repo root
./gradlew build
```

This builds and tests both modules, producing:
- `copilot-chat-reader/build/libs/` -- the reader library/CLI jar
- `copilot-chat-exporter-plugin/build/distributions/` -- the installable plugin ZIP

To run just the reader's CLI harness:
```bash
./gradlew :copilot-chat-reader:run --args="<session-uuid-or-path>"
```

To run the plugin in a sandboxed IntelliJ instance:
```bash
./gradlew :copilot-chat-exporter-plugin:runIde
```

See each module's README for details on usage, architecture, and known limitations.
