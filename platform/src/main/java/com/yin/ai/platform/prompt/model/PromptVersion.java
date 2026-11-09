package com.yin.ai.platform.prompt.model;

import lombok.Data;

/**
 * Prompt 版本记录 — Fragment/Template 的变更历史条目。
 */
@Data
public class PromptVersion {

    /** 版本号 */
    private String version;

    /** 变更日期 */
    private String date;

    /** 变更作者 */
    private String author;

    /** 变更说明 */
    private String change;
}
