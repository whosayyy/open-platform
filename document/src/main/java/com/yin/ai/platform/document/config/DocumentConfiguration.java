package com.yin.ai.platform.document.config;

import com.yin.ai.platform.document.cleaner.DocumentCleaner;
import com.yin.ai.platform.document.cleaner.WhitespaceNormalizerDocumentCleaner;
import com.yin.ai.platform.document.detector.DocumentDetector;
import com.yin.ai.platform.document.detector.SimpleDocumentDetector;
import com.yin.ai.platform.document.parser.DefaultDocumentParserSelector;
import com.yin.ai.platform.document.parser.DocxDocumentParser;
import com.yin.ai.platform.document.parser.DocumentParser;
import com.yin.ai.platform.document.parser.DocumentParserSelector;
import com.yin.ai.platform.document.parser.HtmlDocumentParser;
import com.yin.ai.platform.document.parser.MarkdownDocumentParser;
import com.yin.ai.platform.document.parser.PdfDocumentParser;
import com.yin.ai.platform.document.parser.PptxDocumentParser;
import com.yin.ai.platform.document.parser.TxtDocumentParser;
import com.yin.ai.platform.document.parser.XlsxDocumentParser;
import com.yin.ai.platform.document.processor.DocumentProcessor;
import com.yin.ai.platform.document.processor.WhitespaceNormalizerASTProcessor;
import com.yin.ai.platform.document.runtime.DefaultDocumentRuntime;
import com.yin.ai.platform.document.runtime.DocumentRuntime;
import com.yin.ai.platform.document.splitter.ASTHeadingChunkSplitter;
import com.yin.ai.platform.document.splitter.ChunkSplitter;
import com.yin.ai.platform.document.splitter.HeadingChunkSplitter;
import com.yin.ai.platform.document.splitter.ParagraphChunkSplitter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Document Runtime 模块 Spring 配置。
 */
@Configuration
@ConditionalOnProperty(name = "ai.document.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentConfiguration {

    @Bean
    @ConfigurationProperties("ai.document")
    DocumentProperties documentProperties() {
        return new DocumentProperties();
    }

    // ——— Detector ———

    @Bean
    DocumentDetector documentDetector() {
        return new SimpleDocumentDetector();
    }

    // ——— Parsers ———

    @Bean
    MarkdownDocumentParser markdownDocumentParser() {
        return new MarkdownDocumentParser();
    }

    @Bean
    HtmlDocumentParser htmlDocumentParser() {
        return new HtmlDocumentParser();
    }

    @Bean
    TxtDocumentParser txtDocumentParser() {
        return new TxtDocumentParser();
    }

    @Bean
    PdfDocumentParser pdfDocumentParser() {
        return new PdfDocumentParser();
    }

    @Bean
    DocxDocumentParser docxDocumentParser() {
        return new DocxDocumentParser();
    }

    @Bean
    XlsxDocumentParser xlsxDocumentParser() {
        return new XlsxDocumentParser();
    }

    @Bean
    PptxDocumentParser pptxDocumentParser() {
        return new PptxDocumentParser();
    }

    // ——— Selector ———

    @Bean
    DocumentParserSelector documentParserSelector(List<DocumentParser> parsers) {
        return new DefaultDocumentParserSelector(parsers);
    }

    // ——— Cleaners ———

    @Bean
    WhitespaceNormalizerDocumentCleaner whitespaceNormalizerDocumentCleaner() {
        return new WhitespaceNormalizerDocumentCleaner();
    }

    // ——— Splitters ———

    @Bean
    HeadingChunkSplitter headingChunkSplitter(DocumentProperties props) {
        return new HeadingChunkSplitter(props.getDefaultChunkSize(), props.getDefaultChunkOverlap());
    }

    @Bean
    ParagraphChunkSplitter paragraphChunkSplitter(DocumentProperties props) {
        return new ParagraphChunkSplitter(props.getDefaultChunkSize(), props.getDefaultChunkOverlap());
    }

    @Bean
    ASTHeadingChunkSplitter astHeadingChunkSplitter() {
        return new ASTHeadingChunkSplitter();
    }

    // ——— AST Processors ———

    @Bean
    WhitespaceNormalizerASTProcessor whitespaceNormalizerASTProcessor() {
        return new WhitespaceNormalizerASTProcessor();
    }

    // ——— Runtime ———

    @Bean
    DocumentRuntime documentRuntime(DocumentDetector detector,
                                    DocumentParserSelector selector,
                                    List<DocumentCleaner> cleaners,
                                    List<ChunkSplitter> splitters,
                                    List<DocumentProcessor> processors) {
        return new DefaultDocumentRuntime(detector, selector, cleaners, splitters, processors);
    }
}
