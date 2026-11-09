package com.yin.ai.platform.document.runtime;

import com.yin.ai.platform.document.cleaner.DocumentCleaner;
import com.yin.ai.platform.document.detector.DocumentDetector;
import com.yin.ai.platform.document.exception.DocumentParseException;
import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;
import com.yin.ai.platform.document.model.ParsedDocument;
import com.yin.ai.platform.document.parser.DocumentParser;
import com.yin.ai.platform.document.parser.DocumentParserSelector;
import com.yin.ai.platform.document.source.DocumentSource;
import com.yin.ai.platform.document.splitter.ChunkSplitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DefaultDocumentRuntime")
class DefaultDocumentRuntimeTest {

    @Mock
    private DocumentDetector detector;
    @Mock
    private DocumentParserSelector selector;
    @Mock
    private DocumentCleaner cleaner;
    @Mock
    private ChunkSplitter splitter;
    @Mock
    private DocumentSource source;
    @Mock
    private DocumentParser parser;

    private DefaultDocumentRuntime runtime;
    private DocumentInput input;
    private DocumentTypeInfo typeInfo;

    @BeforeEach
    void setUp() throws IOException {
        runtime = new DefaultDocumentRuntime(detector, selector, List.of(cleaner), List.of(splitter));

        input = DocumentInput.builder()
                .rawBytes("Hello World".getBytes())
                .fileName("test.md")
                .size(11)
                .build();

        typeInfo = DocumentTypeInfo.builder()
                .documentType(DocumentType.MARKDOWN)
                .mimeType("text/markdown")
                .confidence(1.0)
                .build();

        when(source.resolve()).thenReturn(input);
        when(detector.detect(input)).thenReturn(typeInfo);
    }

    @Test
    @DisplayName("全链路正常流程")
    void shouldExecuteFullPipeline() throws Exception {
        when(selector.select(input, typeInfo)).thenReturn(Optional.of(parser));
        when(parser.parse(input, typeInfo)).thenReturn(
                ParsedDocument.builder().documentId("id1").title("Test").content("content").build());
        when(parser.name()).thenReturn("MockParser");
        when(cleaner.supports(any())).thenReturn(true);
        when(splitter.supports(any())).thenReturn(true);
        when(splitter.split(any())).thenReturn(List.of());

        ParsedDocument result = runtime.process(source);

        assertThat(result.getDocumentId()).isNotNull();
        assertThat(result.getTitle()).isEqualTo("Test");
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Source resolve 失败返回 error document")
    void shouldReturnErrorDocWhenSourceFails() throws IOException {
        when(source.resolve()).thenThrow(new IOException("File not found"));

        ParsedDocument result = runtime.process(source);

        assertThat(result.getDocumentType()).isEqualTo(DocumentType.UNKNOWN);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("Source resolve failed"));
    }

    @Test
    @DisplayName("Parser 异常降级为 raw fallback + warning")
    void shouldFallbackOnParserError() throws Exception {
        when(selector.select(input, typeInfo)).thenReturn(Optional.of(parser));
        when(parser.parse(input, typeInfo)).thenThrow(new DocumentParseException(
                DocumentParseException.Code.PARSE_ERROR, "Parse error"));
        when(parser.name()).thenReturn("MockParser");
        when(cleaner.supports(any())).thenReturn(false);
        when(splitter.supports(any())).thenReturn(false);

        ParsedDocument result = runtime.process(source);

        assertThat(result.getWarnings()).anyMatch(w -> w.contains("Parse failed"));
        assertThat(result.getContent()).isEqualTo("Hello World");
    }

    @Test
    @DisplayName("无 Parser 匹配 → raw fallback + warning")
    void shouldFallbackWhenNoParserSelected() {
        when(selector.select(input, typeInfo)).thenReturn(Optional.empty());
        when(cleaner.supports(any())).thenReturn(false);
        when(splitter.supports(any())).thenReturn(false);

        ParsedDocument result = runtime.process(source);

        assertThat(result.getParserName()).isEqualTo("raw-fallback");
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("No parser found"));
    }

    @Test
    @DisplayName("Cleaner 异常记录 warning 后继续")
    void shouldRecordCleanerErrorAndContinue() throws Exception {
        when(selector.select(input, typeInfo)).thenReturn(Optional.of(parser));
        when(parser.parse(input, typeInfo)).thenReturn(
                ParsedDocument.builder().documentId("id1").content("content").build());
        when(parser.name()).thenReturn("MockParser");
        when(cleaner.supports(any())).thenReturn(true);
        doThrow(new RuntimeException("Cleaner error")).when(cleaner).clean(any());
        when(splitter.supports(any())).thenReturn(false);

        ParsedDocument result = runtime.process(source);

        assertThat(result.getWarnings()).anyMatch(w -> w.contains("Cleaner"));
    }

    @Test
    @DisplayName("Splitter 异常记录 warning 后继续")
    void shouldRecordSplitterErrorAndContinue() throws Exception {
        when(selector.select(input, typeInfo)).thenReturn(Optional.of(parser));
        when(parser.parse(input, typeInfo)).thenReturn(
                ParsedDocument.builder().documentId("id1").content("content").build());
        when(parser.name()).thenReturn("MockParser");
        when(cleaner.supports(any())).thenReturn(false);
        when(splitter.supports(any())).thenReturn(true);
        when(splitter.split(any())).thenThrow(new RuntimeException("Split error"));

        ParsedDocument result = runtime.process(source);

        assertThat(result.getWarnings()).anyMatch(w -> w.contains("Splitter"));
    }
}
