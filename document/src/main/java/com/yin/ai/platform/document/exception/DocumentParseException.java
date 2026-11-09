package com.yin.ai.platform.document.exception;

/**
 * Document Runtime 解析异常 — 检查型异常，强制调用方处理解析失败。
 */
public class DocumentParseException extends Exception {

    public enum Code {
        UNSUPPORTED_TYPE,
        IO_ERROR,
        PARSE_ERROR,
        ENCODING_ERROR,
        SIZE_EXCEEDED;
    }

    private final Code code;

    public DocumentParseException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public DocumentParseException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
