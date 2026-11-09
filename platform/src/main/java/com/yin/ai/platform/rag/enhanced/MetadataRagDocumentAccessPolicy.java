package com.yin.ai.platform.rag.enhanced;

import com.yin.ai.platform.rollout.RoadmapFeatureProperties;
import org.springframework.stereotype.Component;

/** Rechecks namespace metadata so a retriever or stale projection cannot bypass isolation. */
@Component
public class MetadataRagDocumentAccessPolicy implements RagDocumentAccessPolicy {

    private final RoadmapFeatureProperties rollout;

    public MetadataRagDocumentAccessPolicy(RoadmapFeatureProperties rollout) {
        this.rollout = rollout;
    }

    @Override
    public boolean canRead(RetrievedDocument document, String tenantId, Long userId) {
        if (document == null) return false;
        String namespace = document.metadata() != null ? document.metadata().get("namespace") : null;
        if (namespace == null || namespace.isBlank()) {
            return !rollout.getTenantIsolation().isEnforcing();
        }
        if (namespace.startsWith("public:")) return true;
        if (tenantId != null && !tenantId.isBlank() && namespace.startsWith(tenantId + ":")) return true;
        return userId != null && namespace.startsWith(userId + ":");
    }

    @Override
    public int priority() {
        return 100;
    }
}
