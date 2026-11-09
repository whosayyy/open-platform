package com.yin.ai.platform.core.agent.approval;

import java.util.Optional;

/** Core SPI that decides whether a plan step must pause for external approval. */
@FunctionalInterface
public interface ApprovalPolicy {

    ApprovalPolicy NONE = context -> Optional.empty();

    Optional<ApprovalRequest> evaluate(ApprovalContext context);
}
