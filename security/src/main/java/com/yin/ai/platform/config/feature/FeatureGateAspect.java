package com.yin.ai.platform.config.feature;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @FeatureGate AOP 切面 — 在方法执行前检查 flag 状态。
 */
@Aspect
public class FeatureGateAspect {

    private static final Logger log = LoggerFactory.getLogger(FeatureGateAspect.class);

    private final FeatureFlagStore flagStore;

    public FeatureGateAspect(FeatureFlagStore flagStore) {
        this.flagStore = flagStore;
    }

    @Around("@annotation(featureGate)")
    public Object checkFeature(ProceedingJoinPoint joinPoint, FeatureGate featureGate) throws Throwable {
        String key = featureGate.value();
        if (!flagStore.isEnabled(key)) {
            log.info("[FEATURE-GATE] blocked: {}", key);
            throw new FeatureDisabledException(key);
        }
        return joinPoint.proceed();
    }
}
