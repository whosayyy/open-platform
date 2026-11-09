package com.yin.ai.platform.document.parser;

import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentTypeInfo;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 基于优先级的 Parser 选择器 — 按 {@link DocumentParser#priority()} 降序遍历，首个 {@code supports()} 匹配即返回。
 */
public class DefaultDocumentParserSelector implements DocumentParserSelector {

    private final List<DocumentParser> parsers;

    public DefaultDocumentParserSelector(List<DocumentParser> parsers) {
        this.parsers = parsers.stream()
                .sorted(Comparator.comparingInt(DocumentParser::priority).reversed())
                .toList();
    }

    @Override
    public Optional<DocumentParser> select(DocumentInput input, DocumentTypeInfo typeInfo) {
        for (DocumentParser parser : parsers) {
            if (parser.supports(input, typeInfo)) {
                return Optional.of(parser);
            }
        }
        return Optional.empty();
    }
}
