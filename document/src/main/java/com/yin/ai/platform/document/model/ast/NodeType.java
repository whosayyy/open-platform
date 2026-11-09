package com.yin.ai.platform.document.model.ast;

/**
 * Document AST 节点类型枚举。
 */
public enum NodeType {
    ROOT,
    SECTION,
    PARAGRAPH,
    TABLE,
    TABLE_ROW,
    TABLE_CELL,
    IMAGE,
    CODE_BLOCK,
    LIST,
    LIST_ITEM,
    QUOTE,
    FORMULA,
    PAGE,
    ENTITY,
    UNKNOWN;
}
