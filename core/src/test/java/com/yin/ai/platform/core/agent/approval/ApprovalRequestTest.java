package com.yin.ai.platform.core.agent.approval;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRequestTest {

    @Test
    void roundTripsAsPortableScalarCollections() {
        ApprovalRequest request = new ApprovalRequest("approval-1", "task-1", "step-1",
                "publish", "external side effect", ApprovalRisk.CRITICAL,
                Map.of("roles", List.of("editor")), Instant.parse("2026-08-21T00:00:00Z"),
                Instant.parse("2026-08-21T00:05:00Z"));

        ApprovalRequest restored = ApprovalRequest.fromPortableMap(request.toPortableMap());

        assertThat(restored).isEqualTo(request);
        assertThat(restored.toPortableMap().toString())
                .doesNotContain("ApprovalRequest", "com.yin");
    }
}
