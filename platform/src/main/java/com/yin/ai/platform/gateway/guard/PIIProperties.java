package com.yin.ai.platform.gateway.guard;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PII 检测配置属性 — {@code ai.guard.pii.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.guard.pii")
public class PIIProperties {

    /** 是否启用 PII 检测 */
    private boolean enabled = true;

    /** 身份证号检测 */
    private boolean idCardEnabled = true;

    /** 手机号检测 */
    private boolean phoneEnabled = true;

    /** 银行卡号检测 */
    private boolean bankCardEnabled = true;

    /** 邮箱检测 */
    private boolean emailEnabled = true;

    /** 地址检测 */
    private boolean addressEnabled = true;
}
