package com.xvpn.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Panel v1 catalog backed exclusively by the xvpn.node.v1 contract.
 *
 * Raw share links and legacy sing-box "config" blobs are intentionally not
 * accepted here. The Panel owns parsing/normalization; Android only consumes
 * the validated profile object and builds a local Mihomo profile.
 */
final class NodeCatalog {
    static final String CATALOG_SCHEMA = "xvpn.nodes.v1";
    static final String NODE_SCHEMA = "xvpn.node.v1";
    static final String CORE = "mihomo";

    final List<Country> countries = new ArrayList<>();
    int total;
    int skippedInvalid;

    static NodeCatalog fromBootstrap(JSONObject bootstrap) {
        if (bootstrap == null) throw new IllegalArgumentException("Panel 未返回启动数据");
        requireValue(bootstrap.optString("api", "v1"), "v1", "Panel API 版本不兼容");
        requireValue(bootstrap.optString("core", ""), CORE, "Panel 内核类型不是 Mihomo");
        requireValue(bootstrap.optString("node_schema", ""), NODE_SCHEMA, "Panel 节点格式不兼容");

        JSONObject payload = bootstrap.optJSONObject("nodes");
        if (payload == null) throw new IllegalArgumentException("Panel 未返回节点目录");
        requireValue(payload.optString("schema", ""), CATALOG_SCHEMA, "Panel 节点目录格式不兼容");
        requireValue(payload.optString("node_schema", ""), NODE_SCHEMA, "Panel 节点格式不兼容");
        requireValue(payload.optString("core", ""), CORE, "节点目录不是 Mihomo 数据");

        NodeCatalog out = new NodeCatalog();
        out.total = payload.optInt("total", 0);
        out.skippedInvalid = payload.optInt("skipped_invalid", 0);
        JSONArray countries = payload.optJSONArray("countries");
        if (countries == null) return out;

        for (int i = 0; i < countries.length(); i++) {
            JSONObject sourceCountry = countries.optJSONObject(i);
            if (sourceCountry == null) continue;
            Country country = new Country();
            country.name = clean(sourceCountry.optString("country", "其他"), "其他");
            country.code = clean(sourceCountry.optString("country_code", "ZZ"), "ZZ").toUpperCase(Locale.ROOT);
            country.flag = clean(sourceCountry.optString("flag_emoji", "🌐"), "🌐");
            country.sortOrder = sourceCountry.optInt("sort_order", 999999);

            JSONArray nodes = sourceCountry.optJSONArray("nodes");
            if (nodes != null) {
                for (int j = 0; j < nodes.length(); j++) {
                    JSONObject sourceNode = nodes.optJSONObject(j);
                    if (sourceNode == null) continue;
                    try {
                        country.nodes.add(parseNode(sourceNode, country, j));
                    } catch (IllegalArgumentException ignored) {
                        out.skippedInvalid++;
                    }
                }
            }
            if (!country.nodes.isEmpty()) out.countries.add(country);
        }
        return out;
    }

    private static Node parseNode(JSONObject source, Country country, int fallbackOrder) {
        int id = source.optInt("id", 0);
        if (id <= 0) throw new IllegalArgumentException("节点 ID 无效");
        JSONObject profile = source.optJSONObject("profile");
        validateProfile(profile);

        Node node = new Node();
        node.id = id;
        node.name = clean(source.optString("display_name", source.optString("name", "节点")), "节点");
        node.country = clean(source.optString("country", country.name), country.name);
        node.countryCode = clean(source.optString("country_code", country.code), country.code).toUpperCase(Locale.ROOT);
        node.region = clean(source.optString("region", ""), "");
        node.protocol = normalizedProtocol(profile.optString("protocol", ""));
        node.server = clean(profile.optString("server", ""), "");
        node.port = profile.optInt("port", 0);
        node.sortOrder = source.optInt("sort_order", fallbackOrder);
        node.flag = country.flag;
        try {
            node.profile = new JSONObject(profile.toString());
        } catch (Exception error) {
            throw new IllegalArgumentException("节点标准数据无法复制", error);
        }
        return node;
    }

    private static void validateProfile(JSONObject profile) {
        if (profile == null) throw new IllegalArgumentException("节点缺少 profile");
        requireValue(profile.optString("schema", ""), NODE_SCHEMA, "节点 schema 不兼容");
        String protocol = normalizedProtocol(profile.optString("protocol", ""));
        switch (protocol) {
            case "vless":
            case "vmess":
            case "trojan":
            case "shadowsocks":
            case "hysteria2":
            case "tuic":
            case "anytls":
                break;
            default:
                throw new IllegalArgumentException("节点协议不受支持");
        }
        String server = profile.optString("server", "").trim();
        int port = profile.optInt("port", 0);
        if (server.isEmpty() || port <= 0 || port > 65535) {
            throw new IllegalArgumentException("节点服务器或端口无效");
        }
        if (profile.optJSONObject("auth") == null || profile.optJSONObject("tls") == null
                || profile.optJSONObject("transport") == null || profile.optJSONObject("options") == null) {
            throw new IllegalArgumentException("节点标准字段不完整");
        }
    }

    private static String normalizedProtocol(String value) {
        String protocol = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("ss".equals(protocol)) return "shadowsocks";
        if ("hy2".equals(protocol)) return "hysteria2";
        return protocol;
    }

    private static void requireValue(String actual, String expected, String message) {
        if (!expected.equalsIgnoreCase(actual == null ? "" : actual.trim())) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String clean(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text;
    }

    Node firstNode() {
        for (Country country : countries) if (!country.nodes.isEmpty()) return country.nodes.get(0);
        return null;
    }

    Node find(int id) {
        if (id <= 0) return null;
        for (Country country : countries) for (Node node : country.nodes) if (node.id == id) return node;
        return null;
    }

    static final class Country {
        String name;
        String code;
        String flag;
        int sortOrder;
        final List<Node> nodes = new ArrayList<>();
    }

    static final class Node {
        int id;
        int sortOrder;
        int port;
        String name;
        String country;
        String countryCode;
        String region;
        String protocol;
        String server;
        String flag;
        JSONObject profile;
    }
}
