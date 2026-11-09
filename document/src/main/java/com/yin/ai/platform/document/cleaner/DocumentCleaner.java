package com.yin.ai.platform.document.cleaner;

import com.yin.ai.platform.document.model.ParsedDocument;

/**
 * DocumentCleaner SPI — 文档后处理清洗器。
 * <p>
 * 多个 Cleaner 按 {@link #priority()} 升序依次执行。
 */
public interface DocumentCleaner {

    default String name() {
        return getClass().getSimpleName();
    }

    /** 执行顺序，值越小越先执行。 */
    default int priority() {
        return 100;
    }

    default boolean supports(ParsedDocument document) {
        return true;
    }

    void clean(ParsedDocument document);
}
