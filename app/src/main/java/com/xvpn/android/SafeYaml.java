package com.xvpn.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/** Minimal deterministic YAML emitter for Mihomo profiles built from org.json values. */
final class SafeYaml {
    private SafeYaml() {}

    static String dump(JSONObject root) throws Exception {
        if (root == null) throw new IllegalArgumentException("YAML 根对象为空");
        StringBuilder out = new StringBuilder(2048);
        appendObject(out, root, 0);
        return out.toString();
    }

    private static void appendObject(StringBuilder out, JSONObject object, int indent) throws Exception {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            indent(out, indent);
            out.append(quote(key)).append(':');
            appendValueAfterKey(out, value, indent);
        }
    }

    private static void appendArray(StringBuilder out, JSONArray array, int indent) throws Exception {
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            indent(out, indent);
            out.append('-');
            if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                if (object.length() == 0) out.append(" {}\n");
                else {
                    out.append('\n');
                    appendObject(out, object, indent + 2);
                }
            } else if (value instanceof JSONArray) {
                JSONArray nested = (JSONArray) value;
                if (nested.length() == 0) out.append(" []\n");
                else {
                    out.append('\n');
                    appendArray(out, nested, indent + 2);
                }
            } else {
                out.append(' ').append(scalar(value)).append('\n');
            }
        }
    }

    private static void appendValueAfterKey(StringBuilder out, Object value, int indent) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.length() == 0) out.append(" {}\n");
            else {
                out.append('\n');
                appendObject(out, object, indent + 2);
            }
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() == 0) out.append(" []\n");
            else {
                out.append('\n');
                appendArray(out, array, indent + 2);
            }
            return;
        }
        out.append(' ').append(scalar(value)).append('\n');
    }

    private static String scalar(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        return quote(String.valueOf(value));
    }

    /**
     * YAML double-quoted scalar using only YAML-defined escapes. In particular,
     * slash is never escaped as \/ (valid JSON, invalid YAML), and every literal
     * backslash becomes \\ so passwords/paths cannot create unknown escapes.
     */
    static String quote(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length() + 16).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x7f || c == 0x85 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format(java.util.Locale.US, "\\u%04X", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    private static void indent(StringBuilder out, int spaces) {
        for (int i = 0; i < spaces; i++) out.append(' ');
    }
}
