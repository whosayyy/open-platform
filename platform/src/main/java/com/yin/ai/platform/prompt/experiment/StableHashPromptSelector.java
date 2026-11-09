package com.yin.ai.platform.prompt.experiment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 based assignment that remains stable across nodes and JVM restarts. */
public class StableHashPromptSelector implements PromptSelector {

    @Override
    public PromptSelection select(PromptExperiment experiment, PromptSelectionContext context) {
        int bucket = bucket(experiment.id(), context);
        if (!experiment.enabled() || experiment.variants().isEmpty()) {
            return PromptSelection.fallback(experiment, bucket);
        }
        int upperBound = 0;
        for (PromptVariant variant : experiment.variants()) {
            upperBound += variant.weight();
            if (bucket < upperBound) {
                return new PromptSelection(experiment.id(), variant.id(), variant.promptVersion(), bucket, false);
            }
        }
        return PromptSelection.fallback(experiment, bucket);
    }

    private int bucket(String experimentId, PromptSelectionContext context) {
        String key = value(context.tenantId()) + '\u001f' + value(context.userId()) + '\u001f'
                + value(context.sessionId()) + '\u001f' + experimentId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            return Math.floorMod(ByteBuffer.wrap(digest).getInt(), PromptExperiment.BUCKET_COUNT);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
