package com.yin.ai.platform.gateway.cache;

/**
 * 语义缓存条目 — 存储 embedding + response 的关联。
 */
public class CacheEntry {

    private float[] embedding;
    private String content;
    private String model;
    private long createdAt;

    public CacheEntry() {}

    public CacheEntry(float[] embedding, String content, String model) {
        this.embedding = embedding;
        this.content = content;
        this.model = model;
        this.createdAt = System.currentTimeMillis();
    }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
