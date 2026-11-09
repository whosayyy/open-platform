package com.yin.ai.platform.agent.runtime;

/**
 * SubAgent 嵌套深度超限异常。
 */
public class DepthLimitExceededException extends RuntimeException {

    private final int parentDepth;
    private final int attemptedDepth;
    private final int maxDepth;

    public DepthLimitExceededException(int parentDepth, int attemptedDepth, int maxDepth) {
        super("SubAgent depth limit exceeded: parent=" + parentDepth
                + " attempted=" + attemptedDepth + " max=" + maxDepth);
        this.parentDepth = parentDepth;
        this.attemptedDepth = attemptedDepth;
        this.maxDepth = maxDepth;
    }

    public int parentDepth() { return parentDepth; }
    public int attemptedDepth() { return attemptedDepth; }
    public int maxDepth() { return maxDepth; }
}
