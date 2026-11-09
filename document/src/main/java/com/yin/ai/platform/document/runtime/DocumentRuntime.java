package com.yin.ai.platform.document.runtime;

import com.yin.ai.platform.document.model.DocumentRuntimeOptions;
import com.yin.ai.platform.document.model.ParsedDocument;
import com.yin.ai.platform.document.source.DocumentSource;

/**
 * DocumentRuntime — Document Pipeline 顶层编排入口。
 */
public interface DocumentRuntime {

    ParsedDocument process(DocumentSource source);

    ParsedDocument process(DocumentSource source, DocumentRuntimeOptions options);
}
