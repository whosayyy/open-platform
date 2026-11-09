package com.yin.ai.platform.agent.observer;

/**
 * 观察结论。
 *
 * @param status  执行状态（SUCCESS / PARTIAL / FAILURE）
 * @param summary 观察摘要
 */
public record Observation(ObservationStatus status, String summary) {

    public enum ObservationStatus {
        SUCCESS,
        PARTIAL,
        FAILURE
    }

    public static Observation success(String summary) {
        return new Observation(ObservationStatus.SUCCESS, summary);
    }

    public static Observation failure(String summary) {
        return new Observation(ObservationStatus.FAILURE, summary);
    }
}
