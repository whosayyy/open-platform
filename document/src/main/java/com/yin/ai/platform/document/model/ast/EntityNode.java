package com.yin.ai.platform.document.model.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * 实体节点 — NER 产出，标注文档中的命名实体。
 */
public final class EntityNode extends DocumentNode {

    private EntityType entityType;
    private String name;
    private List<String> aliases;
    private Double confidence;
    private String referenceNodeId;

    public EntityNode() {
        super(NodeType.ENTITY);
        this.aliases = new ArrayList<>();
    }

    public EntityType getEntityType() {
        return entityType;
    }

    public void setEntityType(EntityType entityType) {
        this.entityType = entityType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
        this.aliases = aliases != null ? aliases : new ArrayList<>();
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getReferenceNodeId() {
        return referenceNodeId;
    }

    public void setReferenceNodeId(String referenceNodeId) {
        this.referenceNodeId = referenceNodeId;
    }

    @Override
    public String text() {
        return name;
    }
}
