package com.yin.ai.platform.core.agent.research;

/** Verified source discovered in completed research-step evidence. */
public record ResearchSource(
        String title,
        String url,
        String source,
        String snippet,
        String publishedAt
) {
    public ResearchSource {
        title = clean(title);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        url = url.trim();
        source = clean(source);
        snippet = clean(snippet);
        publishedAt = clean(publishedAt);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
