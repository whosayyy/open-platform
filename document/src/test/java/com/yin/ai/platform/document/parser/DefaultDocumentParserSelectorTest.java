package com.yin.ai.platform.document.parser;

import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultDocumentParserSelector")
class DefaultDocumentParserSelectorTest {

    @Mock
    private DocumentParser markdownParser;
    @Mock
    private DocumentParser htmlParser;
    @Mock
    private DocumentParser txtParser;

    private DefaultDocumentParserSelector selector;
    private DocumentInput input;
    private DocumentTypeInfo mdInfo;

    @BeforeEach
    void setUp() {
        when(markdownParser.priority()).thenReturn(100);
        when(htmlParser.priority()).thenReturn(90);
        when(txtParser.priority()).thenReturn(10);

        selector = new DefaultDocumentParserSelector(List.of(markdownParser, htmlParser, txtParser));
        input = DocumentInput.builder().fileName("test.md").build();
        mdInfo = DocumentTypeInfo.builder().documentType(DocumentType.MARKDOWN).build();
    }

    @Test
    @DisplayName("选择第一个 support() 的 Parser（优先级最高）")
    void shouldSelectFirstMatchingParser() {
        when(markdownParser.supports(input, mdInfo)).thenReturn(true);

        Optional<DocumentParser> result = selector.select(input, mdInfo);
        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(markdownParser);
    }

    @Test
    @DisplayName("跳过不匹配的，选下一个匹配的")
    void shouldSkipNonMatchingParser() {
        when(markdownParser.supports(input, mdInfo)).thenReturn(false);
        when(htmlParser.supports(input, mdInfo)).thenReturn(true);

        Optional<DocumentParser> result = selector.select(input, mdInfo);
        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(htmlParser);
    }

    @Test
    @DisplayName("所有 Parser 都不匹配 → empty")
    void shouldReturnEmptyWhenNoParserMatches() {
        when(markdownParser.supports(input, mdInfo)).thenReturn(false);
        when(htmlParser.supports(input, mdInfo)).thenReturn(false);
        when(txtParser.supports(input, mdInfo)).thenReturn(false);

        Optional<DocumentParser> result = selector.select(input, mdInfo);
        assertThat(result).isEmpty();
    }
}
