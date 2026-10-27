package io.github.tortaje.fitnesse.health;

import java.util.Collection;
import java.util.Map;

/** Minimal JSON writer for the value types produced by health checks. */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                appendString(out, String.valueOf(entry.getKey()));
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Collection<?> collection) {
            appendArray(out, collection.toArray());
        } else if (value instanceof Object[] array) {
            appendArray(out, array);
        } else if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            Object[] items = new Object[length];
            for (int i = 0; i < length; i++) {
                items[i] = java.lang.reflect.Array.get(value, i);
            }
            appendArray(out, items);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Number number) {
            appendNumber(out, number);
        } else {
            appendString(out, value.toString());
        }
    }

    private static void appendArray(StringBuilder out, Object[] items) {
        out.append('[');
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            append(out, items[i]);
        }
        out.append(']');
    }

    private static void appendNumber(StringBuilder out, Number number) {
        if (number instanceof Double d && !Double.isFinite(d)) {
            appendString(out, d.toString());
        } else if (number instanceof Float f && !Float.isFinite(f)) {
            appendString(out, f.toString());
        } else {
            out.append(number);
        }
    }

    private static void appendString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
