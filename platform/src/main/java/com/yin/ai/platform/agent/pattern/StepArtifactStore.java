package com.yin.ai.platform.agent.pattern;

/**
 * Persistence boundary for worker outputs produced by a plan step.
 *
 * <p>The platform remains independent from MinIO. A service module can
 * provide this optional adapter; without it, Redis snapshots still preserve
 * the workflow state.</p>
 */
public interface StepArtifactStore {

    ArtifactRef save(String taskId, String stepId, int stepIndex, String content);

    /**
     * Verify that the stored object can be read back and matches the bytes that were written.
     * Final task completion is forbidden until this succeeds.
     */
    default Verification verify(ArtifactRef ref) {
        return new Verification(ref != null, ref != null ? ref.size() : 0,
                ref != null ? ref.sha256() : null, ref == null ? "missing_artifact_ref" : "");
    }

    record ArtifactRef(String name, String objectKey, String url, long size, String sha256) {
        public ArtifactRef(String name, String objectKey, String url, long size) {
            this(name, objectKey, url, size, null);
        }
    }

    record Verification(boolean verified, long size, String sha256, String error) {
    }
}
