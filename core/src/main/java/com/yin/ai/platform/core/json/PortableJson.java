package com.yin.ai.platform.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dependency-free JSON document reader for portable core protocols. */
public final class PortableJson {

    private PortableJson() { }

    /** Reads the first complete JSON object or array embedded in the supplied text. */
    public static Object parseDocument(String content) {
        String json = extractDocument(content);
        return json == null ? null : new JsonReader(json).read();
    }

    private static String extractDocument(String content) {
        if (content == null || content.isBlank()) return null;
        int start = -1;
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (current == '{' || current == '[') {
                start = i;
                break;
            }
        }
        if (start < 0) return null;
        char opening = content.charAt(start);
        char closing = opening == '{' ? '}' : ']';
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < content.length(); i++) {
            char current = content.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') quoted = true;
            else if (current == opening) depth++;
            else if (current == closing && --depth == 0) return content.substring(start, i + 1);
        }
        return null;
    }

    private static final class JsonReader {
        private final String source;
        private int cursor;

        private JsonReader(String source) {
            this.source = source;
        }

        Object read() {
            Object value = readValue();
            whitespace();
            if (cursor != source.length()) throw error("Unexpected trailing content");
            return value;
        }

        private Object readValue() {
            whitespace();
            if (cursor >= source.length()) throw error("Unexpected end of JSON");
            return switch (source.charAt(cursor)) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (consume('}')) return result;
            do {
                whitespace();
                String key = readString();
                whitespace();
                expect(':');
                result.put(key, readValue());
                whitespace();
            } while (consume(','));
            expect('}');
            return result;
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            whitespace();
            if (consume(']')) return result;
            do {
                result.add(readValue());
                whitespace();
            } while (consume(','));
            expect(']');
            return result;
        }

        private String readString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (cursor < source.length()) {
                char current = source.charAt(cursor++);
                if (current == '"') return result.toString();
                if (current != '\\') {
                    result.append(current);
                    continue;
                }
                if (cursor >= source.length()) throw error("Incomplete escape");
                char escaped = source.charAt(cursor++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(readUnicode());
                    default -> throw error("Unknown escape: " + escaped);
                }
            }
            throw error("Unterminated string");
        }

        private char readUnicode() {
            if (cursor + 4 > source.length()) throw error("Incomplete unicode escape");
            try {
                char value = (char) Integer.parseInt(source.substring(cursor, cursor + 4), 16);
                cursor += 4;
                return value;
            } catch (NumberFormatException exception) {
                throw error("Invalid unicode escape");
            }
        }

        private Number readNumber() {
            int start = cursor;
            while (cursor < source.length()) {
                char current = source.charAt(cursor);
                if (!(Character.isDigit(current) || current == '-' || current == '+'
                        || current == '.' || current == 'e' || current == 'E')) break;
                cursor++;
            }
            if (start == cursor) throw error("Expected JSON value");
            String value = source.substring(start, cursor);
            try {
                return value.contains(".") || value.contains("e") || value.contains("E")
                        ? Double.parseDouble(value) : Long.parseLong(value);
            } catch (NumberFormatException exception) {
                throw error("Invalid number: " + value);
            }
        }

        private Object literal(String expected, Object value) {
            if (!source.startsWith(expected, cursor)) throw error("Expected " + expected);
            cursor += expected.length();
            return value;
        }

        private void whitespace() {
            while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++;
        }

        private boolean consume(char expected) {
            if (cursor < source.length() && source.charAt(cursor) == expected) {
                cursor++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) throw error("Expected '" + expected + "'");
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + cursor);
        }
    }
}
