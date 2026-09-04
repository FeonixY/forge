package forge.web;

import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON writer. Just enough to emit the GameView contract
 * (objects, arrays, strings, numbers, booleans). Kept deliberately tiny so the
 * bridge pulls in no JSON library.
 */
public final class Json {
    private Json() {}

    /** Serialize a value (Map, List, String, Number, Boolean, null) to a JSON string. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v.toString());
        } else if (v instanceof Number n) {
            // Avoid NaN/Infinity which aren't valid JSON.
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("0");
            } else if (d == Math.rint(d)) {
                sb.append(Long.toString((long) d));
            } else {
                sb.append(n.toString());
            }
        } else if (v instanceof Map<?, ?> m) {
            writeObject(sb, (Map<String, Object>) m);
        } else if (v instanceof List<?> list) {
            writeArray(sb, list);
        } else {
            // Fallback: treat unknown types as strings.
            writeString(sb, v.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, e.getKey());
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        boolean first = true;
        for (Object o : list) {
            if (!first) sb.append(',');
            first = false;
            writeValue(sb, o);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
