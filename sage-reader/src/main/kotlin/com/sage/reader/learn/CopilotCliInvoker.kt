package com.sage.reader.learn

import java.nio.file.Path

/** Thrown when the `copilot` CLI cannot be found/started, or exits non-zero. */
class CopilotCliException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Shells out to the officially-supported `copilot` CLI, in its documented
 * non-interactive scripting mode (`-p`/`--prompt`), to perform the actual
 * "analyse sessions and learn from mistakes" work.
 *
 * This deliberately does NOT talk to any GitHub Copilot HTTP API directly.
 * An earlier spike tried exchanging the CLI's own stored GitHub OAuth token
 * (found via Windows Credential Manager) for a Copilot token through the
 * undocumented `api.github.com/copilot_internal/v2/token` endpoint; that
 * request came back `403 Forbidden` with GitHub's explicit anti-scraping/ToS
 * message. Automating the already-installed, already-authenticated `copilot`
 * CLI as a subprocess instead uses the user's real Copilot subscription
 * through a fully supported entry point, with no reverse-engineering
 * involved -- see `copilot --help` for the documented `-p`, `--allow-all-tools`,
 * `--add-dir`, and `--model` flags this class relies on.
 *
 * Because the `copilot` CLI itself has file read/write tools, callers don't
 * need to embed session content in [prompt] text -- they just point it at
 * file paths via [addDirs] (e.g. a temp dir of rendered session transcripts,
 * plus the directory containing the instructions file to update) and let
 * the CLI's own agent loop read/merge/write those files.
 */
class CopilotCliInvoker @JvmOverloads constructor(
    private val processRunner: ProcessRunner = RealProcessRunner(),
    private val executablePath: String = defaultExecutablePath()
) {

    /**
     * Runs `copilot -p <prompt> --allow-all-tools --add-dir <dir> ... [--model <model>]`
     * non-interactively and returns the captured result.
     *
     * @param prompt the full instruction text for Copilot to execute.
     * @param addDirs directories Copilot's file tools are allowed to read/write
     *   without prompting (each becomes one `--add-dir` argument).
     * @param model optional model name passed straight through as `--model`;
     *   if null, Copilot picks its own default.
     * @param workingDir optional working directory for the subprocess
     *   (`-C <dir>`); if null, uses the current process's working directory.
     * @param onOutputLine optional callback invoked live, once per line of
     *   the CLI's stdout as it's produced, so callers can surface real-time
     *   progress (e.g. into a UI status indicator) instead of a static
     *   message until the whole run finishes.
     * @throws CopilotCliException if the `copilot` executable can't be
     *   started, or exits with a non-zero code.
     */
    @JvmOverloads
    fun runPrompt(
        prompt: String,
        addDirs: List<Path>,
        model: String? = null,
        workingDir: Path? = null,
        onOutputLine: ((String) -> Unit)? = null
    ): ProcessResult {
        val command = mutableListOf(
            executablePath,
            "-p", prompt,
            "--allow-all-tools",
            "--no-color",
            "--output-format", "text"
        )
        for (dir in addDirs.distinct()) {
            command += listOf("--add-dir", dir.toString())
        }
        if (!model.isNullOrBlank()) {
            command += listOf("--model", model)
        }
        if (workingDir != null) {
            command += listOf("-C", workingDir.toString())
        }

        val result = try {
            processRunner.run(command, workingDir, onOutputLine)
        } catch (e: Exception) {
            throw CopilotCliException(
                "Could not run the 'copilot' CLI (executable: '$executablePath'). " +
                    "Make sure GitHub Copilot CLI is installed and on PATH, or set " +
                    "the SAGE_CLI_PATH environment variable to its " +
                    "full path.",
                e
            )
        }

        if (result.exitCode != 0) {
            throw CopilotCliException(
                "'copilot' CLI exited with code ${result.exitCode}.\n" +
                    "--- stderr ---\n${result.stderr}\n" +
                    "--- stdout ---\n${result.stdout}"
            )
        }
        return result
    }

    companion object {
        /**
         * Common install locations for the `copilot` CLI on Unix-like
         * systems, checked only as a last-resort fallback if resolving via
         * a login shell (see [resolveViaLoginShell]) also fails (e.g. no
         * shell available). Covers the most common installs: Homebrew on
         * Apple Silicon/Intel Macs, and typical npm global-prefix locations.
         */
        private val UNIX_FALLBACK_PATHS = listOf(
            "/opt/homebrew/bin/copilot",
            "/usr/local/bin/copilot",
            System.getProperty("user.home") + "/.npm-global/bin/copilot",
            System.getProperty("user.home") + "/.local/bin/copilot"
        )

        /**
         * Resolves the `copilot` executable to invoke: an explicit override
         * via the `SAGE_CLI_PATH` environment variable takes priority.
         * Otherwise, on Unix-like systems, asks the user's actual login
         * shell to resolve it via `command -v copilot` (see
         * [resolveViaLoginShell]), since GUI apps (including IntelliJ,
         * launched via Launch Services/Finder/Dock rather than a login
         * shell) do NOT inherit the shell's `PATH` or exported env vars --
         * a `copilot` install that works fine from Terminal can otherwise be
         * invisible to the IDE process. If the shell probe is unavailable
         * or fails, a short list of well-known install locations is checked
         * (see [UNIX_FALLBACK_PATHS]) before finally falling back to the
         * bare command name (`copilot` on Unix-like systems, `copilot.cmd`
         * on Windows, matching how npm installs its CLI shims) and relying
         * on this process's own `PATH` resolution.
         */
        fun defaultExecutablePath(): String {
            val override = System.getenv("SAGE_CLI_PATH")
            if (!override.isNullOrBlank()) return override
            val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
            if (!isWindows) {
                resolveViaLoginShell()?.let { return it }
                val found = UNIX_FALLBACK_PATHS.firstOrNull { java.io.File(it).canExecute() }
                if (found != null) return found
            }
            return if (isWindows) "copilot.cmd" else "copilot"
        }

        /**
         * Runs the user's login shell (from the `$SHELL` env var, defaulting
         * to `/bin/zsh`, macOS's default since Catalina) in `-l` (login)
         * mode to source the user's actual shell profile (`.zprofile`,
         * `.zshrc`, `.bash_profile`, etc.) and resolve `copilot` the same
         * way Terminal would, via `command -v copilot`. Returns the
         * resolved absolute path, or null if the shell isn't available, the
         * probe times out, or `copilot` isn't found this way.
         */
        private fun resolveViaLoginShell(): String? {
            return try {
                val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
                val process = ProcessBuilder(shell, "-lc", "command -v copilot")
                    .redirectErrorStream(true)
                    .start()
                val finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return null
                }
                if (process.exitValue() != 0) return null
                val resolved = process.inputStream.bufferedReader().readText().trim()
                resolved.takeIf { it.isNotEmpty() && java.io.File(it).canExecute() }
            } catch (e: Exception) {
                null
            }
        }
    }
}
