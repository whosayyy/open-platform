package com.yin.ai.platform.document.parser;

import com.yin.ai.platform.document.exception.DocumentParseException;
import com.yin.ai.platform.document.model.DocumentInput;
import com.yin.ai.platform.document.model.DocumentType;
import com.yin.ai.platform.document.model.DocumentTypeInfo;
import com.yin.ai.platform.document.model.ParsedDocument;
import com.yin.ai.platform.document.model.ast.DocumentAST;
import com.yin.ai.platform.document.model.ast.ParagraphNode;
import com.yin.ai.platform.document.model.ast.RootNode;
import com.yin.ai.platform.document.model.ast.SectionNode;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PPTX 演示文稿解析器 — 基于 Apache POI，将 PowerPoint 转换为 Markdown 格式。
 * <p>
 * 转换规则：
 * <ol>
 *   <li>文件标题 → # 一级标题</li>
 *   <li>每张幻灯片标题 → ## 二级标题</li>
 *   <li>幻灯片内文本形状 → 段落文本</li>
 * </ol>
 */
public class PptxDocumentParser implements DocumentParser {

    @Override
    public int priority() {
        return 70;
    }

    @Override
    public boolean supports(DocumentInput input, DocumentTypeInfo typeInfo) {
        return typeInfo.getDocumentType() == DocumentType.PPTX;
    }

    @Override
    public Set<DocumentType> supportedTypes() {
        return Set.of(DocumentType.PPTX);
    }

    @Override
    public ParsedDocument parse(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(input.getRawBytes()))) {
            String title = stripExtension(input.getFileName());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileName", input.getFileName());
            metadata.put("fileSize", input.getSize());
            metadata.put("sourceType", input.getSourceType());
            metadata.put("slideCount", ppt.getSlides().size());

            StringBuilder md = new StringBuilder();
            md.append("# ").append(title).append("\n\n");

            List<XSLFSlide> slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                String slideTitle = slide.getTitle();

                if (slideTitle != null && !slideTitle.isBlank()) {
                    md.append("## ").append(slideTitle).append("\n\n");
                } else {
                    md.append("## Slide ").append(i + 1).append("\n\n");
                }

                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        // skip title placeholder — already handled above
                        if (isTitlePlaceholder(textShape, slideTitle)) {
                            continue;
                        }

                        List<XSLFTextParagraph> paragraphs = textShape.getTextParagraphs();
                        for (XSLFTextParagraph para : paragraphs) {
                            String text = para.getText();
                            if (text != null && !text.isBlank()) {
                                // Detect bullet points
                                if (para.getIndentLevel() > 0) {
                                    md.append("  ".repeat(Math.max(0, para.getIndentLevel() - 1)))
                                            .append("- ").append(text.trim()).append("\n");
                                } else {
                                    md.append(text.trim()).append("\n\n");
                                }
                            }
                        }
                    }
                }

                md.append("\n");
            }

            String content = md.toString().stripTrailing();

            return ParsedDocument.builder()
                    .documentId(UUID.randomUUID().toString())
                    .documentType(DocumentType.PPTX)
                    .mimeType(typeInfo.getMimeType())
                    .title(title)
                    .content(content)
                    .contentLength(content.length())
                    .parserName(name())
                    .parserVersion(version())
                    .metadata(metadata)
                    .build();

        } catch (IOException e) {
            throw new DocumentParseException(
                    DocumentParseException.Code.PARSE_ERROR,
                    "Failed to parse PPTX: " + e.getMessage(), e);
        }
    }

    @Override
    public DocumentAST parseToAST(DocumentInput input, DocumentTypeInfo typeInfo) throws DocumentParseException {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(input.getRawBytes()))) {
            RootNode root = new RootNode();
            root.setStartOffset(0);
            root.setEndOffset(input.getSize());
            root.setTitle(stripExtension(input.getFileName()));

            List<XSLFSlide> slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                String slideTitle = slide.getTitle();

                SectionNode section = new SectionNode();
                section.setLevel(2);
                section.setHeading(slideTitle != null && !slideTitle.isBlank()
                        ? slideTitle : "Slide " + (i + 1));

                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        if (isTitlePlaceholder(textShape, slideTitle)) {
                            continue;
                        }
                        for (XSLFTextParagraph para : textShape.getTextParagraphs()) {
                            String text = para.getText();
                            if (text != null && !text.isBlank()) {
                                ParagraphNode paraNode = new ParagraphNode();
                                paraNode.setText(text.trim());
                                section.addChild(paraNode);
                            }
                        }
                    }
                }
                root.addChild(section);
            }

            DocumentAST ast = DocumentAST.of(root);
            ast.setDocumentId(UUID.randomUUID().toString());
            return ast;

        } catch (IOException e) {
            throw new DocumentParseException(
                    DocumentParseException.Code.PARSE_ERROR,
                    "Failed to parse PPTX AST: " + e.getMessage(), e);
        }
    }

    private boolean isTitlePlaceholder(XSLFTextShape shape, String slideTitle) {
        if (slideTitle == null || slideTitle.isBlank()) {
            return false;
        }
        String shapeText = shape.getText();
        return shapeText != null && shapeText.trim().equals(slideTitle.trim());
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
