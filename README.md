# Sage

A multi-module Gradle project that analyses GitHub Copilot chat sessions for mistakes and automatically turns them into lasting instructions, so future sessions learn from the past. Sage also supports exporting sessions to Markdown, from either the JetBrains IDE plugin or the GitHub Copilot CLI.

## Modules

- **[`sage-reader`](sage-reader/README.md)** -- Plain Kotlin library (no IntelliJ dependency) with a `--learn` command that analyses one or more Copilot chat sessions (IDE Nitrite `.db` files and CLI JSONL sessions) for mistakes and merges the lessons into a Copilot instructions file. Also discovers, parses, and renders sessions to Markdown, with a CLI harness for standalone use and testing.
- **[`sage-plugin`](sage-plugin/README.md)** -- IntelliJ IDE plugin that wraps the reader in a single **Tools → Summon Sage** entry point, which asks whether to "Seek Enlightenment" (multi-select sessions, analyse them with the `copilot` CLI, merge the lessons into a project and/or global instructions file) or "Preserve the Holy Texts" (export session picker, settings page, file save/reveal integration).

## Requirements

- **An active GitHub Copilot subscription** (individual, business, or enterprise) is required to use Sage. Sage itself doesn't call any Copilot/GitHub API directly -- it reads chat data already saved locally by the Copilot IDE plugin or CLI, and (for the "Analyse & Update Learnings" feature) shells out to the `copilot` CLI, which needs a signed-in, licensed Copilot session to run.
- For **Analyse Sessions & Update Learnings** (the primary feature): the `copilot` CLI must be installed, on `PATH` (or pointed to via the `SAGE_CLI_PATH` environment variable), and signed in with a licensed subscription -- this feature runs real agentic reasoning per invocation and is not instant.
- For **export**: at least one prior Copilot chat session, from either:
  - the GitHub Copilot plugin in a JetBrains IDE, or
  - the GitHub Copilot CLI (`copilot`)
- JDK 11+ and IntelliJ 2024.2+ (Build 242+) if building/running the plugin from source.
- Windows and JetBrains IDE paths are the primary tested target; see each module's README for platform-specific notes (e.g. Windows file-locking behavior).

## Building

Requires JDK 11+. The project uses the Gradle wrapper, so no separate Gradle install is needed.

```bash
# From the repo root
./gradlew build
```

This builds and tests both modules, producing:
- `sage-reader/build/libs/` -- the reader library/CLI jar
- `sage-plugin/build/distributions/` -- the installable plugin ZIP

To run just the reader's CLI harness:
```bash
./gradlew :sage-reader:run --args="<session-uuid-or-path>"
```

To run the plugin in a sandboxed IntelliJ instance:
```bash
./gradlew :sage-plugin:runIde
```

See each module's README for details on usage, architecture, and known limitations.
