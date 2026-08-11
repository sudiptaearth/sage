package com.sage.reader.learn

import java.nio.file.Path
import java.nio.file.Paths

/** Which scope a generated learnings/instructions file applies to. */
enum class InstructionsScope { PROJECT, GLOBAL }

/**
 * Resolves where the "learnings" instructions file(s) should live, matching
 * the real conventions GitHub Copilot CLI/IDE already use on disk (verified
 * against this machine's own installation, not guessed):
 *
 * - **Project**: `<repo-root>/.github/copilot-instructions.md` -- this is
 *   exactly the file `copilot init` itself generates (see `copilot init --help`),
 *   so it's already picked up by Copilot for that repository.
 * - **Global**: `~/.copilot/instructions/<name>.instructions.md` -- matches
 *   the real `~/.copilot/instructions/guidelines.instructions.md` file found
 *   on this machine, which starts with an `applyTo: '**'` YAML frontmatter
 *   block and is loaded for every session regardless of project.
 */
object InstructionsFileTargets {

    const val DEFAULT_GLOBAL_FILE_NAME = "learnings.instructions.md"

    /** `<repoRoot>/.github/copilot-instructions.md` */
    fun projectPath(repoRoot: Path): Path =
        repoRoot.resolve(".github").resolve("copilot-instructions.md")

    /** `~/.copilot/instructions/<fileName>` */
    @JvmOverloads
    fun globalPath(
        homeDir: String = System.getProperty("user.home"),
        fileName: String = DEFAULT_GLOBAL_FILE_NAME
    ): Path = Paths.get(homeDir, ".copilot", "instructions", fileName)

    /** Resolves the requested scopes to their concrete file paths. */
    @JvmOverloads
    fun resolve(
        scopes: Set<InstructionsScope>,
        repoRoot: Path? = null,
        homeDir: String = System.getProperty("user.home"),
        globalFileName: String = DEFAULT_GLOBAL_FILE_NAME
    ): List<Path> {
        val paths = mutableListOf<Path>()
        if (InstructionsScope.PROJECT in scopes) {
            val root = requireNotNull(repoRoot) { "repoRoot is required to resolve a PROJECT instructions target" }
            paths.add(projectPath(root))
        }
        if (InstructionsScope.GLOBAL in scopes) {
            paths.add(globalPath(homeDir, globalFileName))
        }
        return paths
    }
}
