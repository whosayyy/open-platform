package com.yin.ai.platform.document.detector;

import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SimpleDocumentDetector")
class SimpleDocumentDetectorTest {

    private SimpleDocumentDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SimpleDocumentDetector();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            report.md   | MARKDOWN
            page.html   | HTML
            manual.pdf  | PDF
            notes.txt   | TXT
            data.csv    | CSV
            config.json | JSON
            data.xml    | XML
            archive.zip | ZIP
            photo.png   | IMAGE
            photo.JPG   | IMAGE
            """)
    @DisplayName("扩展名映射检测 (confidence=1.0)")
    void shouldDetectByExtension(String fileName, DocumentType expectedType) {
        DocumentInput input = DocumentInput.builder()
                .rawBytes("test".getBytes(StandardCharsets.UTF_8))
                .fileName(fileName)
                .size(4)
                .build();

        DocumentTypeInfo info = detector.detect(input);
        assertThat(info.getDocumentType()).isEqualTo(expectedType);
        assertThat(info.getConfidence()).isEqualTo(1.0);
        assertThat(info.getDetectionMethod()).isEqualTo("extension");
    }

    @Test
    @DisplayName("无扩展名但内容含 <html> → HTML content sniff")
    void shouldSniffHtmlContent() {
        String html = "<!DOCTYPE html><html><body>Hello</body></html>";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(html.getBytes(StandardCharsets.UTF_8))
                .fileName("unknown")
                .size(html.length())
                .build();

        DocumentTypeInfo info = detector.detect(input);
        assertThat(info.getDocumentType()).isEqualTo(DocumentType.HTML);
        assertThat(info.getDetectionMethod()).isEqualTo("content_sniff");
        assertThat(info.getConfidence()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("PDF Magic Bytes (%PDF-) 检测")
    void shouldDetectPdfByMagicBytes() {
        byte[] pdfMagic = "%PDF-1.4\nrest of pdf...".getBytes(StandardCharsets.UTF_8);
        DocumentInput input = DocumentInput.builder()
                .rawBytes(pdfMagic)
                .fileName("noextension")
                .size(pdfMagic.length)
                .build();

        DocumentTypeInfo info = detector.detect(input);
        assertThat(info.getDocumentType()).isEqualTo(DocumentType.PDF);
        assertThat(info.getDetectionMethod()).isEqualTo("magic_bytes");
        assertThat(info.getConfidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("UTF-8 可解码但无扩展名 → TXT fallback")
    void shouldFallbackToTxtIfUtf8Decodable() {
        String text = "Plain text content";
        DocumentInput input = DocumentInput.builder()
                .rawBytes(text.getBytes(StandardCharsets.UTF_8))
                .fileName("noextension")
                .size(text.length())
                .build();

        DocumentTypeInfo info = detector.detect(input);
        assertThat(info.getDocumentType()).isEqualTo(DocumentType.TXT);
        assertThat(info.getConfidence()).isEqualTo(0.5);
        assertThat(info.getDetectionMethod()).isEqualTo("utf8_fallback");
    }

    @Test
    @DisplayName("完全未知 → UNKNOWN")
    void shouldReturnUnknownForUnrecognizableContent() {
        byte[] binary = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        DocumentInput input = DocumentInput.builder()
                .rawBytes(binary)
                .fileName("data.bin")
                .size(binary.length)
                .build();

        DocumentTypeInfo info = detector.detect(input);
        assertThat(info.getDocumentType()).isEqualTo(DocumentType.UNKNOWN);
        assertThat(info.getConfidence()).isEqualTo(0.3);
        assertThat(info.getDetectionMethod()).isEqualTo("fallback");
    }

    @Test
    @DisplayName("null fileName 正确处理")
    void shouldHandleNullFileName() {
        DocumentInput input = DocumentInput.builder()
                .rawBytes("hello".getBytes(StandardCharsets.UTF_8))
                .fileName(null)
                .size(5)
                .build();

        DocumentTypeInfo info = detector.detect(input);
        assertThat(info.getDocumentType()).isEqualTo(DocumentType.TXT);
        assertThat(info.getExtension()).isNull();
    }
}
