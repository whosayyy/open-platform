package com.yin.ai.platform.workflow.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 工作流任务请求 — 由应用层构造，进入 ExecutionRuntime.start()。
 */
public record TaskRequest(
        String goal,
        Long userId,
        String conversationId,
        Map<String, Object> context,
        boolean explicitPublishRequested,
        boolean stageByStageMode
) {

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, Object> contextOrEmpty() {
        return context == null ? new HashMap<>() : context;
    }

    public static class Builder {
        private String goal;
        private Long userId;
        private String conversationId;
        private Map<String, Object> context = new HashMap<>();
        private boolean explicitPublishRequested;
        private boolean stageByStageMode;

        public Builder goal(String goal) {
            this.goal = goal;
            return this;
        }

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder context(Map<String, Object> context) {
            this.context = context == null ? new HashMap<>() : context;
            return this;
        }

        public Builder explicitPublishRequested(boolean v) {
            this.explicitPublishRequested = v;
            return this;
        }

        public Builder stageByStageMode(boolean v) {
            this.stageByStageMode = v;
            return this;
        }

        public TaskRequest build() {
            return new TaskRequest(goal, userId, conversationId, context, explicitPublishRequested, stageByStageMode);
        }
    }
}
