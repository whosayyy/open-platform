package com.yin.ai.platform.document.model.ast;

/**
 * 实体类型 — 用于 NER 产出标注。
 */
public enum EntityType {
    PERSON,
    COMPANY,
    LOCATION,
    ORGANIZATION,
    TIME,
    DATE,
    PRODUCT,
    EVENT,
    TERM,
    CONCEPT,
    DOCUMENT_REF,
    URL,
    EMAIL,
    PHONE,
    CUSTOM;
}
