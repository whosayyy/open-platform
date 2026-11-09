package com.yin.ai.platform.exception;

/**
 * AI Gateway 统一异常 — 封装 Provider 调用中的错误。
 */
public class GatewayException extends RuntimeException {

    public enum Code {
        PROVIDER_ERROR,
        RATE_LIMIT,
        TIMEOUT,
        INVALID_REQUEST,
        INVALID_RESPONSE,
        UNAUTHORIZED,
        OVERLOAD
    }

    private final Code code;
    private final String provider;

    public GatewayException(Code code, String provider, String message) {
        super(message);
        this.code = code;
        this.provider = provider;
    }

    public GatewayException(Code code, String provider, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.provider = provider;
    }

    public Code code() { return code; }
    public String provider() { return provider; }
}
