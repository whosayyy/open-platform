package com.yin.security.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 服务间内部 API 的 HMAC-SHA256 签名工具。
 * <p>
 * 签名算法：{@code HMAC-SHA256(timestamp + serviceId, secret)}，通过
 * {@code X-Internal-Sign} 与 {@code X-Internal-Timestamp} 请求头传递。
 * <p>
 * 由 WKC AI Platform 自带（vendor 自主工程 {@code wkc-common}）。
 */
public final class InternalApiSignUtils {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 允许的时间戳偏移上限（5 分钟），用于防重放。 */
    public static final long MAX_TIMESTAMP_SKEW_MS = 5 * 60 * 1000L;

    private InternalApiSignUtils() {
    }

    /**
     * 计算内部 API 的 HMAC-SHA256 签名。
     *
     * @param timestamp 当前时间戳（毫秒）
     * @param serviceId 调用方的应用名
     * @param secret    共享密钥
     * @return Base64 编码的签名；任一参数为空时返回 null
     */
    public static String sign(String timestamp, String serviceId, String secret) {
        if (timestamp == null || serviceId == null || secret == null || secret.isEmpty()) {
            return null;
        }
        String data = timestamp + serviceId;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", e);
        }
    }

    /**
     * 校验签名与时间戳新鲜度。
     *
     * @param sign      签名（X-Internal-Sign）
     * @param timestamp 时间戳（X-Internal-Timestamp，毫秒）
     * @param serviceId 期望的调用方应用名
     * @param secret    共享密钥
     * @param maxSkewMs 允许的时间偏移（毫秒）
     * @return 签名有效且在允许窗口内时返回 true
     */
    public static boolean verify(String sign, String timestamp, String serviceId,
                                 String secret, long maxSkewMs) {
        if (sign == null || timestamp == null || serviceId == null
                || secret == null || secret.isEmpty()) {
            return false;
        }
        try {
            long ts = Long.parseLong(timestamp);
            long now = System.currentTimeMillis();
            if (Math.abs(now - ts) > maxSkewMs) {
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        String expectedSign = sign(timestamp, serviceId, secret);
        return expectedSign != null
                && java.security.MessageDigest.isEqual(
                        expectedSign.getBytes(StandardCharsets.UTF_8),
                        sign.getBytes(StandardCharsets.UTF_8));
    }

    /** 使用默认 5 分钟容差校验签名。 */
    public static boolean verify(String sign, String timestamp, String serviceId, String secret) {
        return verify(sign, timestamp, serviceId, secret, MAX_TIMESTAMP_SKEW_MS);
    }
}
