package com.yin.ai.platform.agent.observe;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/** Creates one child span for each agent step. */
public class StepTracer {

    private final Tracer tracer;

    public StepTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    public StepSpan start(String taskId, String stepId, String executor, String mode) {
        Span span = tracer.spanBuilder("agent.step." + safe(stepId)).startSpan();
        span.setAttribute("task.id", safe(taskId));
        span.setAttribute("step.id", safe(stepId));
        span.setAttribute("step.executor", safe(executor));
        span.setAttribute("agent.mode", safe(mode));
        return new StepSpan(span, span.makeCurrent());
    }

    private String safe(String value) {
        return value == null ? "unknown" : value;
    }

    public static final class StepSpan implements AutoCloseable {
        private final Span span;
        private final Scope scope;

        private StepSpan(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public void finish(boolean success, long tokens, String error) {
            span.setAttribute("step.tokens", tokens);
            span.setAttribute("step.success", success);
            if (!success) {
                span.setStatus(StatusCode.ERROR, error == null ? "step failed" : error);
            }
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}
