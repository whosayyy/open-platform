package com.yin.ai.platform.document.detector;

import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;

import java.nio.charset.Charset;
import java.util.Map;

/**
 * 基于文件扩展名 + Magic Bytes 的文档类型检测器。
 * <p>
 * 检测策略（按优先级递减）：
 * <ol>
 *   <li>扩展名映射（confidence=1.0）</li>
 *   <li>Magic Bytes / 内容嗅探（confidence=0.8）</li>
 *   <li>UTF-8 可解码 + 无扩展名 → TXT（confidence=0.5）</li>
 *   <li>兜底 UNKNOWN（confidence=0.3）</li>
 * </ol>
 */
public class SimpleDocumentDetector implements DocumentDetector {

    private static final Map<String, DocumentType> EXTENSION_MAP = Map.ofEntries(
            Map.entry("md", DocumentType.MARKDOWN),
            Map.entry("markdown", DocumentType.MARKDOWN),
            Map.entry("html", DocumentType.HTML),
            Map.entry("htm", DocumentType.HTML),
            Map.entry("pdf", DocumentType.PDF),
            Map.entry("docx", DocumentType.DOCX),
            Map.entry("doc", DocumentType.DOCX),
            Map.entry("xlsx", DocumentType.XLSX),
            Map.entry("xls", DocumentType.XLSX),
            Map.entry("pptx", DocumentType.PPTX),
            Map.entry("ppt", DocumentType.PPTX),
            Map.entry("txt", DocumentType.TXT),
            Map.entry("csv", DocumentType.CSV),
            Map.entry("json", DocumentType.JSON),
            Map.entry("xml", DocumentType.XML),
            Map.entry("zip", DocumentType.ZIP),
            Map.entry("png", DocumentType.IMAGE),
            Map.entry("jpg", DocumentType.IMAGE),
            Map.entry("jpeg", DocumentType.IMAGE),
            Map.entry("gif", DocumentType.IMAGE),
            Map.entry("bmp", DocumentType.IMAGE),
            Map.entry("webp", DocumentType.IMAGE),
            Map.entry("eml", DocumentType.EMAIL),
            Map.entry("msg", DocumentType.EMAIL)
    );

    private static final Map<String, String> EXTENSION_MIME_MAP = Map.ofEntries(
            Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("zip", "application/zip"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg")
    );

    @Override
    public DocumentTypeInfo detect(DocumentInput input) {
        String fileName = input.getFileName();
        String extension = extractExtension(fileName);

        // 1. Extension-based detection
        if (extension != null) {
            DocumentType type = EXTENSION_MAP.get(extension);
            if (type != null) {
                return DocumentTypeInfo.builder()
                        .documentType(type)
                        .mimeType(EXTENSION_MIME_MAP.getOrDefault(extension, "application/octet-stream"))
                        .encoding(detectEncoding(input.getRawBytes()))
                        .fileSize(input.getSize())
                        .extension(extension)
                        .confidence(1.0)
                        .detectionMethod("extension")
                        .build();
            }
        }

        // 2. Content sniffing
        DocumentTypeInfo sniffed = sniffContent(input);
        if (sniffed != null) {
            return sniffed;
        }

        // 3. If UTF-8 decodable, treat as TXT
        if (isUtf8Decodable(input.getRawBytes())) {
            return DocumentTypeInfo.builder()
                    .documentType(DocumentType.TXT)
                    .mimeType("text/plain")
                    .encoding("UTF-8")
                    .fileSize(input.getSize())
                    .extension(extension)
                    .confidence(0.5)
                    .detectionMethod("utf8_fallback")
                    .build();
        }

        // 4. Last resort
        return DocumentTypeInfo.builder()
                .documentType(DocumentType.UNKNOWN)
                .mimeType("application/octet-stream")
                .fileSize(input.getSize())
                .extension(extension)
                .confidence(0.3)
                .detectionMethod("fallback")
                .build();
    }

    private DocumentTypeInfo sniffContent(DocumentInput input) {
        byte[] bytes = input.getRawBytes();
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        String start = new String(bytes, 0, Math.min(bytes.length, 512), java.nio.charset.StandardCharsets.UTF_8);

        // HTML detection
        if (start.contains("<!DOCTYPE") || start.contains("<html") || start.contains("<HTML")) {
            return DocumentTypeInfo.builder()
                    .documentType(DocumentType.HTML)
                    .mimeType("text/html")
                    .encoding("UTF-8")
                    .fileSize(input.getSize())
                    .confidence(0.8)
                    .detectionMethod("content_sniff")
                    .build();
        }

        // Markdown detection: starts with heading
        if (start.stripLeading().startsWith("# ")) {
            return DocumentTypeInfo.builder()
                    .documentType(DocumentType.MARKDOWN)
                    .mimeType("text/markdown")
                    .encoding("UTF-8")
                    .fileSize(input.getSize())
                    .confidence(0.8)
                    .detectionMethod("content_sniff")
                    .build();
        }

        // PDF magic bytes
        if (bytes.length >= 4
                && bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46) {
            return DocumentTypeInfo.builder()
                    .documentType(DocumentType.PDF)
                    .mimeType("application/pdf")
                    .fileSize(input.getSize())
                    .confidence(0.9)
                    .detectionMethod("magic_bytes")
                    .build();
        }

        return null;
    }

    private String extractExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase();
    }

    private String detectEncoding(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (isUtf8Decodable(bytes)) {
            return "UTF-8";
        }
        return null;
    }

    private boolean isUtf8Decodable(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        try {
            Charset.availableCharsets().get("UTF-8").newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
