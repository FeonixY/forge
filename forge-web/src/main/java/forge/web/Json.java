package forge.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON writer + reader. The writer emits the GameView
 * contract (objects, arrays, strings, numbers, booleans); the reader parses a
 * standard JSON document into Map / List / String / Double / Boolean / null.
 * Kept deliberately tiny so the bridge pulls in no JSON library.
 */
public final class Json {
    private Json() {}

    // ------------------------------------------------------------------
    // Reader: parse a JSON document into Java objects.
    // Object -> LinkedHashMap<String,Object>, Array -> ArrayList<Object>,
    // String -> String, Number -> Double, true/false -> Boolean, null -> null.
    // ------------------------------------------------------------------

    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (!p.eof()) throw new IllegalArgumentException("Trailing data at " + p.pos);
        return v;
    }

    private static final class Parser {
        final String s;
        int pos;
        Parser(String s) { this.s = s; }

        boolean eof() { return pos >= s.length(); }
        char peek() { return s.charAt(pos); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object readValue() {
            char c = peek();
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default:  return readNumber();
            }
        }

        Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (!eof() && peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                if (peek() != ':') throw err("':' expected");
                pos++;
                skipWs();
                m.put(key, readValue());
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; break; }
                throw err("',' or '}' expected");
            }
            return m;
        }

        List<Object> readArray() {
            List<Object> a = new ArrayList<>();
            pos++; // [
            skipWs();
            if (!eof() && peek() == ']') { pos++; return a; }
            while (true) {
                skipWs();
                a.add(readValue());
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; break; }
                throw err("',' or ']' expected");
            }
            return a;
        }

        String readString() {
            if (peek() != '"') throw err("'\"' expected");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw err("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw err("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object readNumber() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') pos++;
                else break;
            }
            String num = s.substring(start, pos);
            if (num.isEmpty()) throw err("value expected");
            return Double.valueOf(num);
        }

        void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw err("'" + lit + "' expected");
            pos += lit.length();
        }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException("JSON parse error at " + pos + ": " + msg);
        }
    }

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
