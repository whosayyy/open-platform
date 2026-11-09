package com.yin.ai.platform.gateway.guard;

import java.util.List;

/**
 * PII（个人敏感信息）检测器 SPI。
 * <p>
 * 输入端：调用 {@link #detect(String)}，命中 BLOCK 类型则拒绝请求。
 * 输出端：调用 {@link #mask(String)} 对 LLM 输出脱敏后返回用户。
 * 审计日志：调用 {@link #mask(String)} 后落库。
 * <p>
 * 默认实现：{@link RegexPIIDetector}（正则匹配 5 种 PII 类型）。
 * 业务层可通过注册自定义 {@link PIIDetector} Bean 扩展（如 NER 模型检测）。
 */
public interface PIIDetector {

    /**
     * 检测文本中的 PII 并返回匹配结果。
     *
     * @param text 待检测文本
     * @return 检测结果；无 PII 时返回 {@link PIIResult#NONE}
     */
    PIIResult detect(String text);

    /**
     * 对文本中的 PII 进行脱敏处理（如 138****1234）。
     *
     * @param text 原始文本
     * @return 脱敏后文本
     */
    String mask(String text);

    // ── PII 检测结果 ──

    /** 无 PII 检测结果常量 */
    PIIResult NONE = new PIIResult(List.of(), List.of());

    /**
     * PII 检测结果。
     */
    record PIIResult(List<PIIMatch> matches, List<String> matchedTypes) {

        /** 是否有 BLOCK 级别的匹配项 */
        public boolean blocked() {
            return matches.stream().anyMatch(m -> m.type().action() == PIIAction.BLOCK);
        }

        /** 是否有任何匹配项 */
        public boolean detected() {
            return !matches.isEmpty();
        }
    }

    /** 单个 PII 匹配项 */
    record PIIMatch(PIIType type, String original, String masked, int start, int end) {}

    /** PII 类型定义 */
    record PIIType(
            String name,
            String regex,
            String maskPattern,
            PIIAction action,
            String displayName
    ) {}

    /** PII 处理动作 */
    enum PIIAction {
        /** 输入端拦截，输出端脱敏 */
        BLOCK,
        /** 仅脱敏，不拦截 */
        MASK
    }
}
