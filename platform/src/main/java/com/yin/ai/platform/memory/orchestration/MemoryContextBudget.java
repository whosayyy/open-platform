package com.yin.ai.platform.memory.orchestration;

/** Token allocation for each cognitive memory class. */
public record MemoryContextBudget(
        int totalTokens, int workingTokens, int episodicTokens,
        int semanticTokens, int proceduralTokens) {

    public MemoryContextBudget {
        totalTokens = Math.max(0, totalTokens);
        workingTokens = Math.max(0, workingTokens);
        episodicTokens = Math.max(0, episodicTokens);
        semanticTokens = Math.max(0, semanticTokens);
        proceduralTokens = Math.max(0, proceduralTokens);
        if ((long) workingTokens + episodicTokens + semanticTokens + proceduralTokens > totalTokens) {
            throw new IllegalArgumentException("memory token allocations exceed totalTokens");
        }
    }

    public static MemoryContextBudget balanced(int totalTokens) {
        int total = Math.max(0, totalTokens);
        int working = total / 10;
        int episodic = total * 35 / 100;
        int semantic = total * 40 / 100;
        return new MemoryContextBudget(total, working, episodic, semantic,
                total - working - episodic - semantic);
    }
}
