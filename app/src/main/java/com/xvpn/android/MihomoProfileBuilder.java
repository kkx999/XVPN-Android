package com.xvpn.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Builds a complete Mihomo YAML profile from Panel v1's xvpn.node.v1 object. */
final class MihomoProfileBuilder {
    static final int TUN_MTU = 1400;
    static final String CORE_LABEL = "Mihomo " + BuildConfig.MIHOMO_CORE_VERSION + " · Clash Meta";
    static final String NETWORK_PROFILE = "双栈 TUN · MTU 1400 · 分流 DNS";
    static final String PROXY_NAME = "XVPN-PROXY";
    static final String GROUP_NAME = "XVPN";
    static final String CN_DOMAIN_PROVIDER = "XVPN-CN-DOMAIN";
    static final String CN_IP_PROVIDER = "XVPN-CN-IP";
    static final String CN_DOMAIN_RULE_PATH = "./rules/geosite-cn.mrs";
    static final String CN_IP_RULE_PATH = "./rules/geoip-cn.mrs";

    private MihomoProfileBuilder() {}

    static String build(Context ignored, NodeCatalog.Node node, RouteMode mode) throws Exception {
        if (node == null || node.profile == null) throw new IllegalArgumentException("节点配置为空");
        JSONObject proxy = convertProxy(node.profile);
        boolean global = mode == RouteMode.GLOBAL;

        JSONObject profile = new JSONObject()
                .put("mode", "rule")
                .put("log-level", BuildConfig.DEBUG ? "info" : "warning")
                .put("allow-lan", false)
                .put("ipv6", true)
                .put("tcp-concurrent", true)
                .put("unified-delay", true)
                .put("find-process-mode", "off")
                .put("proxies", new JSONArray().put(proxy))
                .put("proxy-groups", new JSONArray().put(new JSONObject()
                        .put("name", GROUP_NAME)
                        .put("type", "select")
                        .put("proxies", new JSONArray().put(PROXY_NAME))))
                .put("dns", buildDns(global));

        if (!global) profile.put("rule-providers", buildRuleProviders());
        profile.put("rules", buildRules(global));

        // Do not save JSONObject.toString() as config.yaml. JSON is only a
        // subset of YAML until JSON-only escaping (for example \/) appears.
        // SafeYaml emits YAML-defined escapes and preserves literal backslashes.
        return SafeYaml.dump(profile);
    }

    private static String normalizedProxyType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if ("shadowsocks".equals(normalized)) return "ss";
        if ("hy2".equals(normalized)) return "hysteria2";
        return normalized;
    }

    private static JSONObject convertProxy(JSONObject src) throws Exception {
        if (!NodeCatalog.NODE_SCHEMA.equals(src.optString("schema", ""))) {
            throw new IllegalArgumentException("节点不是 xvpn.node.v1 标准");
        }
        String type = normalizedProxyType(src.optString("protocol", ""));
        JSONObject auth = requireObject(src, "auth", "节点缺少 auth");
        JSONObject options = requireObject(src, "options", "节点缺少 options");

        JSONObject out = new JSONObject()
                .put("name", PROXY_NAME)
                .put("type", type)
                .put("server", require(src, "server", "节点缺少服务器地址"))
                .put("port", readPort(src))
                .put("udp", true);

        switch (type) {
            case "vless":
                out.put("uuid", require(auth, "uuid", "VLESS 缺少 UUID"));
                copyIf(options, out, "flow", "flow");
                copyIf(options, out, "packet_encoding", "packet-encoding");
                applyTls(src, out, true, false);
                applyTransport(src, out);
                break;
            case "vmess":
                out.put("uuid", require(auth, "uuid", "VMess 缺少 UUID"));
                out.put("alterId", auth.optInt("alter_id", 0));
                out.put("cipher", first(options.optString("cipher", ""), "auto"));
                applyTls(src, out, true, false);
                applyTransport(src, out);
                break;
            case "trojan":
                out.put("password", require(auth, "password", "Trojan 缺少密码"));
                applyTls(src, out, false, true);
                applyTransport(src, out);
                break;
            case "ss":
                out.put("cipher", require(auth, "method", "Shadowsocks 缺少加密方式"));
                out.put("password", require(auth, "password", "Shadowsocks 缺少密码"));
                String pluginSpec = options.optString("plugin", "").trim();
                if (!pluginSpec.isEmpty()) {
                    String[] parts = pluginSpec.split(";", 2);
                    out.put("plugin", parts[0].trim());
                    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                        out.put("plugin-opts", parsePluginOptions(parts[1]));
                    }
                }
                String pluginOpts = options.optString("plugin_opts", "").trim();
                if (!pluginOpts.isEmpty()) out.put("plugin-opts", parsePluginOptions(pluginOpts));
                break;
            case "hysteria2":
                out.put("password", require(auth, "password", "Hysteria2 缺少密码"));
                copyIf(options, out, "obfs", "obfs");
                copyIf(options, out, "obfs_password", "obfs-password");
                applyTls(src, out, false, true);
                break;
            case "tuic":
                out.put("uuid", require(auth, "uuid", "TUIC 缺少 UUID"));
                out.put("password", require(auth, "password", "TUIC 缺少密码"));
                copyIf(options, out, "congestion_control", "congestion-controller");
                copyIf(options, out, "udp_relay_mode", "udp-relay-mode");
                applyTls(src, out, false, true);
                JSONArray tuicAlpn = options.optJSONArray("alpn");
                if (tuicAlpn != null && tuicAlpn.length() > 0) out.put("alpn", tuicAlpn);
                break;
            case "anytls":
                out.put("password", require(auth, "password", "AnyTLS 缺少密码"));
                copyIf(options, out, "idle_session_check_interval", "idle-session-check-interval");
                copyIf(options, out, "idle_session_timeout", "idle-session-timeout");
                if (options.has("min_idle_session")) out.put("min-idle-session", options.optInt("min_idle_session"));
                applyTls(src, out, false, true);
                break;
            default:
                throw new IllegalArgumentException("Mihomo 测试版暂不支持该协议：" + type);
        }
        return out;
    }

    private static void applyTls(JSONObject src, JSONObject out, boolean explicitTls, boolean useSni) throws Exception {
        JSONObject tls = src.optJSONObject("tls");
        if (tls == null || !tls.optBoolean("enabled", true)) return;
        if (explicitTls) out.put("tls", true);

        String serverName = tls.optString("server_name", "").trim();
        if (!serverName.isEmpty()) out.put(useSni ? "sni" : "servername", serverName);
        if (tls.optBoolean("insecure", false)) out.put("skip-cert-verify", true);
        JSONArray alpn = tls.optJSONArray("alpn");
        if (alpn != null && alpn.length() > 0) out.put("alpn", alpn);

        String fingerprint = tls.optString("fingerprint", "").trim();
        if (!fingerprint.isEmpty()) out.put("client-fingerprint", fingerprint);

        JSONObject reality = tls.optJSONObject("reality");
        if (reality != null && reality.optBoolean("enabled", true)) {
            JSONObject realityOpts = new JSONObject();
            copyIf(reality, realityOpts, "public_key", "public-key");
            copyIf(reality, realityOpts, "short_id", "short-id");
            if (realityOpts.length() == 0) throw new IllegalArgumentException("REALITY 缺少 public key");
            out.put("reality-opts", realityOpts);
            out.put("tls", true);
        }
    }

    private static void applyTransport(JSONObject src, JSONObject out) throws Exception {
        JSONObject transport = src.optJSONObject("transport");
        if (transport == null) return;
        String type = transport.optString("type", "").toLowerCase(Locale.ROOT);
        switch (type) {
            case "ws": {
                out.put("network", "ws");
                JSONObject opts = new JSONObject();
                copyIf(transport, opts, "path", "path");
                String host = transport.optString("host", "").trim();
                if (!host.isEmpty()) opts.put("headers", new JSONObject().put("Host", host));
                out.put("ws-opts", opts);
                return;
            }
            case "grpc": {
                out.put("network", "grpc");
                JSONObject opts = new JSONObject();
                copyIf(transport, opts, "service_name", "grpc-service-name");
                out.put("grpc-opts", opts);
                return;
            }
            case "httpupgrade": {
                out.put("network", "httpupgrade");
                JSONObject opts = new JSONObject();
                copyIf(transport, opts, "path", "path");
                copyIf(transport, opts, "host", "host");
                out.put("http-upgrade-opts", opts);
                return;
            }
            case "http": {
                out.put("network", "h2");
                JSONObject opts = new JSONObject();
                copyIf(transport, opts, "path", "path");
                String host = transport.optString("host", "").trim();
                if (!host.isEmpty()) opts.put("host", new JSONArray().put(host));
                out.put("h2-opts", opts);
                return;
            }
            case "quic":
                out.put("network", "quic");
                return;
            case "tcp":
            case "":
                return;
            default:
                throw new IllegalArgumentException("Mihomo 暂不支持传输类型：" + type);
        }
    }

    private static JSONObject buildDns(boolean global) throws Exception {
        JSONArray domesticDns = new JSONArray()
                .put("https://dns.alidns.com/dns-query")
                .put("https://doh.pub/dns-query");
        JSONObject dns = new JSONObject()
                .put("enable", true)
                .put("ipv6", true)
                .put("use-hosts", true)
                .put("use-system-hosts", true)
                .put("respect-rules", true)
                .put("enhanced-mode", "fake-ip")
                .put("fake-ip-range", "198.18.0.1/16")
                .put("fake-ip-filter-mode", "blacklist")
                .put("fake-ip-filter", new JSONArray()
                        .put("+.lan")
                        .put("+.local")
                        .put("localhost")
                        .put("+.home.arpa"))
                .put("default-nameserver", new JSONArray().put("223.5.5.5").put("119.29.29.29"))
                .put("proxy-server-nameserver", new JSONArray().put("223.5.5.5").put("119.29.29.29"))
                .put("direct-nameserver", domesticDns)
                .put("direct-nameserver-follow-policy", true)
                .put("nameserver", new JSONArray()
                        .put("https://1.1.1.1/dns-query")
                        .put("https://8.8.8.8/dns-query"));
        if (!global) {
            dns.put("nameserver-policy", new JSONObject()
                    .put("rule-set:" + CN_DOMAIN_PROVIDER, domesticDns));
        }
        return dns;
    }

    private static JSONObject buildRuleProviders() throws Exception {
        return new JSONObject()
                .put(CN_DOMAIN_PROVIDER, new JSONObject()
                        .put("type", "file")
                        .put("behavior", "domain")
                        .put("format", "mrs")
                        .put("path", CN_DOMAIN_RULE_PATH))
                .put(CN_IP_PROVIDER, new JSONObject()
                        .put("type", "file")
                        .put("behavior", "ipcidr")
                        .put("format", "mrs")
                        .put("path", CN_IP_RULE_PATH));
    }

    private static JSONArray buildRules(boolean global) {
        JSONArray rules = new JSONArray()
                .put("DOMAIN,localhost,DIRECT")
                .put("DOMAIN-SUFFIX,local,DIRECT")
                .put("DOMAIN-SUFFIX,lan,DIRECT")
                .put("DOMAIN-SUFFIX,home.arpa,DIRECT")
                .put("IP-CIDR,127.0.0.0/8,DIRECT,no-resolve")
                .put("IP-CIDR,10.0.0.0/8,DIRECT,no-resolve")
                .put("IP-CIDR,100.64.0.0/10,DIRECT,no-resolve")
                .put("IP-CIDR,172.16.0.0/12,DIRECT,no-resolve")
                .put("IP-CIDR,192.168.0.0/16,DIRECT,no-resolve")
                .put("IP-CIDR,169.254.0.0/16,DIRECT,no-resolve")
                .put("IP-CIDR,224.0.0.0/4,DIRECT,no-resolve")
                .put("IP-CIDR6,::1/128,DIRECT,no-resolve")
                .put("IP-CIDR6,fc00::/7,DIRECT,no-resolve")
                .put("IP-CIDR6,fe80::/10,DIRECT,no-resolve")
                .put("IP-CIDR6,ff00::/8,DIRECT,no-resolve");
        if (!global) {
            // Domain classification first preserves fake-IP routing, then the
            // CN CIDR provider catches direct-IP traffic and unresolved hosts.
            rules.put("DOMAIN-SUFFIX,cn,DIRECT");
            rules.put("RULE-SET," + CN_DOMAIN_PROVIDER + ",DIRECT");
            rules.put("RULE-SET," + CN_IP_PROVIDER + ",DIRECT,no-resolve");
        }
        rules.put("MATCH," + GROUP_NAME);
        return rules;
    }

    private static int readPort(JSONObject src) throws Exception {
        int port = src.optInt("port", 0);
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("节点端口无效");
        return port;
    }

    private static JSONObject parsePluginOptions(String value) throws Exception {
        JSONObject out = new JSONObject();
        for (String part : value.split(";")) {
            String item = part.trim();
            if (item.isEmpty()) continue;
            int eq = item.indexOf('=');
            if (eq > 0) out.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
            else out.put(item, true);
        }
        return out;
    }

    private static void copyIf(JSONObject src, JSONObject dst, String from, String to) throws Exception {
        if (!src.has(from) || src.isNull(from)) return;
        Object value = src.opt(from);
        if (value instanceof String && ((String) value).trim().isEmpty()) return;
        dst.put(to, value);
    }

    private static String require(JSONObject src, String key, String message) {
        String value = src.optString(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(message);
        return value;
    }

    private static String first(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static JSONObject requireObject(JSONObject src, String key, String message) {
        JSONObject value = src.optJSONObject(key);
        if (value == null) throw new IllegalArgumentException(message);
        return value;
    }

    static Endpoint endpoint(NodeCatalog.Node node) {
        if (node == null || node.server == null || node.server.trim().isEmpty()
                || node.port <= 0 || node.port > 65535) {
            throw new IllegalArgumentException("节点测速地址无效");
        }
        String protocol = normalizedProxyType(node.protocol);
        boolean tcp = !"hysteria2".equals(protocol) && !"tuic".equals(protocol);
        return new Endpoint(node.server.trim(), node.port, tcp);
    }

    static final class Endpoint {
        final String host;
        final int port;
        final boolean tcpProbeSupported;

        Endpoint(String host, int port, boolean tcpProbeSupported) {
            this.host = host;
            this.port = port;
            this.tcpProbeSupported = tcpProbeSupported;
        }
    }
}
