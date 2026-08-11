# Sage Reader

A plain Kotlin module -- no IntelliJ Platform dependency -- that reads GitHub
Copilot chat data straight off disk, maps it into a small domain model
(`ChatSession` / `Turn` / `TurnSide` / `ContentBlock`), and renders it to
Markdown. It stands alone and is testable without a running IDE; the
`sage-plugin` module wraps it in an actual IntelliJ plugin
action.

This module stands alone and is testable without a running IDE.

## What's in here

- `json/Json.kt` -- a small dependency-free JSON parser/pretty-printer. Written
  by hand instead of pulling in Gson/Jackson so that (a) object key order is
  guaranteed preserved, which matters for reconstructing Copilot's block
  ordering, and (b) nothing here can clash with IntelliJ's own bundled Gson
  version later.
- `model/DomainModel.kt` -- `ChatSession`, `Turn`, `TurnSide`, `ContentBlock`
  (Text / Thinking / ToolCall / Context).
- `ContentDecoder.kt` -- decodes a turn's nested, JSON-string-in-JSON-string
  `contents` field into a flat, ordered list of `ContentBlock`s.
- `SessionLocator.kt` -- finds and classifies `.db` files on disk (pure path
  logic, no filesystem access required for the classification itself).
- `NitriteSessionReader.kt` -- opens a `.db` file read-only via the Nitrite
  `EntityDecorator` trick (needed since the real GitHub-internal entity
  classes aren't available to a third-party reader), and maps
  documents into the domain model.
- `MarkdownRenderer.kt` -- Renders a `ChatSession` to a single Markdown
  document: a header with session metadata, then one `## Prompt N` section
  per turn. Thinking blocks and tool calls are wrapped in collapsible
  `<details><summary>` elements; tool input/output is fenced as code with a
  fence long enough to survive backticks already present in the content.
  Walks each turn side's `blocks` in the order `ContentDecoder` produced them
  (Thinking, then a round's reply, then that round's tool calls, possibly
  repeating) -- see the doc comment at the top of `MarkdownRenderer.kt` for why.
- `Cli.kt` -- a manual smoke-test harness (`main()`) that prints a summary of
  everything found, or (`--render`) the actual Markdown output, for
  eyeballing against your own real data.

## Building and running

1. Open `sage-reader/` as a Gradle project in IntelliJ, or build it
   as part of the root `Sage` multi-project build.
2. Let Gradle sync. It uses the Kotlin Gradle plugin + Nitrite.
3. Run the tests first (Gradle tool window -> `Tasks > verification > test`,
   or right-click `src/test/kotlin` -> Run All Tests). All fixtures are
   synthetic/fabricated -- no real chat data is touched by the test suite.
4. Run the CLI harness to sanity-check against your real data:
   - `./gradlew run` -- auto-discovers every `.db` under the OS-standard
     `github-copilot` root and prints a summary of every session/turn/block
     found.
   - `./gradlew run --args="<session-uuid>"` -- **auto-detect & render any
     session (CLI or IDE plugin).** Pass the session UUID and the tool
     figures out where it's stored:
     ```
     ./gradlew run --args="e7563911-05f2-4459-aac1-feb460c882f8"
     ```
   - `./gradlew run --args="C:\path\to\some-copilot.db"` -- summarize one file.
   - `./gradlew run --args="--render C:\path\to\some-copilot.db"` -- print
     the actual rendered Markdown for every session in that file. This is
     the fastest way to eyeball real output before wiring it into a plugin
     action and a save dialog.

### Unified Session Export

The reader supports both IDE plugin sessions and Copilot CLI sessions
(from `~/.copilot/session-state/`):

- **IDE Plugin Sessions**: Stored in `%LOCALAPPDATA%\github-copilot\{IDE}\chat-*\{STORE_ID}\` as Nitrite `.db` files
- **CLI Sessions**: Stored in `~/.copilot/session-state\{SESSION_UUID}\` as JSONL + YAML

Simply pass the session UUID to export either type:
```bash
./gradlew run --args="e7563911-05f2-4459-aac1-feb460c882f8"
```

The tool will:
1. Check if it's a CLI session in `~/.copilot/session-state/`
2. If not found, search IDE plugin `.db` files across all stores
3. Render the matching session to Markdown

This eliminates the need to know where a session is stored -- just provide the UUID.

## Learning from mistakes: `--learn`

`--learn` analyses one or more sessions for mistakes and merges the lessons
into a Copilot instructions file, so future sessions pick them up
automatically:

```bash
./gradlew run --args="--learn <uuid> [<uuid>...] [--project] [--global] [--model <name>]"
```

- Pass one or more session UUIDs (CLI or IDE plugin sessions, same
  auto-detection as above).
- `--project` updates `<repo-root>/.github/copilot-instructions.md` (repo
  root = current working directory). `--global` updates
  `~/.copilot/instructions/learnings.instructions.md`. At least one of the
  two must be given; both may be given together.
- `--model <name>` is passed straight through as `copilot --model <name>`;
  omit it to let Copilot pick its own default.

Under the hood this shells out to the **installed `copilot` CLI** in its
non-interactive scripting mode (`copilot -p ... --allow-all-tools
--add-dir ...`) -- it does not call any HTTP API directly. Copilot itself
reads the rendered session transcripts, reads the existing instructions
file (if any), extracts general lessons from concrete mistakes, and writes
a merged, de-duplicated version back -- this tool only renders the
transcripts and points Copilot at the right files.

**Requirements:**
- The `copilot` CLI must be installed and on `PATH` (or set the
  `SAGE_CLI_PATH` environment variable to its full path).
- A real Copilot subscription/session, since this runs actual agentic
  reasoning per invocation (it is not instant -- expect it to take anywhere
  from several seconds to under a minute depending on session size).

**Windows note:** the `copilot` CLI ships as a `.cmd` shim. Long/multi-line
prompts don't reliably survive `cmd.exe`'s argument quoting when invoked via
`ProcessBuilder`, so `LearningAnalyzer` writes its full instructions to a
temp file and passes only a short one-line `-p` argument telling Copilot to
read and follow that file -- worth knowing if you extend this further.

## Locked databases

If the store you point at is currently open read-write by another process
(typically the IDE that's actively running the GitHub Copilot plugin),
`NitriteSessionReader.read()` tries to fall back to copying the file to a
temp location and reading the copy instead. You'll see a one-line note on
stderr (`Note: '<path>' is locked by another process ... reading from a
temporary copy instead.`) when this kicks in.

**This fallback is not a complete fix, and it's important to understand why:**
On Windows, the lock H2's
MVStore takes is a *whole-file* OS-level lock that blocks not just
`openOrCreate()` calls from other processes but a plain file copy of the
locked file too, for as long as the other process keeps it open. So this
fallback only helps for brief/transient locks -- for a *continuously*-open
session (the normal case: the IDE has the project open right now and keeps
its Copilot database connection open for the whole session), it cannot
succeed no matter how the copy is attempted. When that happens, `read()`
retries the copy a few times and then throws a clear `IOException`
explaining the situation and telling you to close the other process,
instead of leaking a raw "another process has locked a portion of the
file" OS error or silently pretending it worked. There's no way around this
from user-space Java short of something like a Windows Volume Shadow Copy
snapshot (needs elevated privileges, not implemented here).

Practical upshot: exporting a session from a *closed* project/IDE window
works reliably. Exporting the session you're having *right now*, in the
same IDE window that's still open, generally won't -- this is a real
architectural constraint worth keeping in mind.

If a copy does succeed, the temp copy is deleted right after the read
completes, and the returned session data is always labelled with the
original path, not the temp one. See the doc comment on
`NitriteSessionReader.read()` for the additional consistency caveat this
implies even when the copy does succeed (a plain file copy isn't a
guaranteed-atomic hot backup).

## Known gaps

- **Edit sessions** (`chat-edit-sessions` / `NtEditSession`) are read as a
  raw pass-through only -- the whole document pretty-printed into one
  "assistant" block per session. That schema hasn't been explored
  field-by-field yet, so `NitriteSessionReader` doesn't pretend to understand it.
- **Block ordering** is trusted to follow the source JSON object's key
  insertion order (see the rationale comment in `json/Json.kt` and
  `ContentDecoder.kt`). This should be correct based on how JS
  objects/`JSON.stringify` behave, but hasn't been cross-checked against a
  real multi-round agent turn with interleaved thinking/tool-call blocks
  side by side. `AgentRound.data.roundId` is available as a fallback sort
  key if ordering issues ever show up.
- The renderer's Markdown structure (session header fields, exactly what
  counts as "Context", how multi-round agent turns are laid out) hasn't been
  eyeballed against a real multi-round agent session yet -- only against
  synthetic fixtures in `MarkdownRendererTest.kt`. Worth running
  `--render` against a real `chat-agent-sessions` `.db` with a multi-tool-call
  turn before treating the output format as final.

## Privacy note

The CLI harness (`Cli.kt`) only prints truncated one-line previews to your
own terminal -- it doesn't write anything to disk. Still, real chat content
(including code from private repos) will flow through memory when you run it
against your own data.
