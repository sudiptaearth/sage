package com.sage.reader.learn

/**
 * How aggressively [LearningAnalyzer] should propose changes to the target
 * instructions file(s) while analysing sessions for mistakes.
 */
enum class LearningMode {
    /**
     * Suggest the minimum change necessary, or none at all. Only add a new
     * rule if it is clearly useful and tied to a concrete, proven mistake in
     * the transcripts. Only remove/change an existing rule if there's clear
     * evidence it isn't helping or is actively causing harm. When in doubt,
     * leave the file unchanged.
     */
    CONSERVATIVE,

    /**
     * Suggest any addition, update, or removal believed likely to help, even
     * if the connection to a specific transcript is more general. Existing
     * rules judged unhelpful, redundant, or counterproductive may be
     * rewritten or removed, not just ones tied to a mistake in these
     * specific transcripts.
     */
    AGGRESSIVE
}
