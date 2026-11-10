package com.yin.ai.platform.gateway.config;

import lombok.Data;

@Data
public class FilterConfig {
    private FilterItem retry = new FilterItem(true, 20);
    private FilterItem fallback = new FilterItem(true, 21);
    private FilterItem metrics = new FilterItem(true, 30);
    private FilterItem trace = new FilterItem(true, 31);
    private FilterItem auditLog = new FilterItem(true, 40);

    @Data
    public static class FilterItem {
        private boolean enabled = true;
        private int order;

        public FilterItem() {}

        public FilterItem(boolean enabled, int order) {
            this.enabled = enabled;
            this.order = order;
        }
    }
}
