package com.yin.ai.platform.exception;

public class TooManyTasksException extends RuntimeException {
    public TooManyTasksException(int currentCount, int maxCount) {
        super("AI 任务并发已满，当前活跃任务数: " + currentCount + "，最大允许: " + maxCount);
    }
}
