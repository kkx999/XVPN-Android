package com.xvpn.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Converts the existing XVPN/sing-box-shaped node profile into a Mihomo profile. */
final class MihomoProfileBuilder {
    static final int TUN_MTU = 1400;
    static final String CORE_LABEL = "Mihomo " + BuildConfig.MIHOMO_CORE_VERSION + " · Clash Meta";
    static final String PROXY_NAME = "XVPN-PROXY";
    static final String GROUP_NAME = "XVPN";
    static final String CN_DOMAIN_PROVIDER = "XVPN-CN-DOMAIN";
    static final String CN_IP_PROVIDER = "XVPN-CN-IP";
    static final String CN_DOMAIN_RULE_PATH = "./rules/geosite-cn.mrs";
    static final String CN_IP_RULE_PATH = "./rules/geoip-cn.mrs";

    private MihomoProfileBuilder() {}

    static String build(String sourceConfig, String routeLabel) throws Exception {
        if (sourceConfig == null || sourceConfig.trim().isEmpty()) {
            throw new IllegalArgumentException("节点配置为空");
        }
        JSONObject source = new JSONObject(sourceConfig);
        JSONObject outbound = findProxyOutbound(source);
        JSONObject proxy = convertProxy(outbound);
        boolean global = RouteMode.GLOBAL.label.equals(routeLabel);

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
                .put("dns", buildDns());

        if (!global) profile.put("rule-providers", buildRuleProviders());
        profile.put("rules", buildRules(global));

        // Do not save JSONObject.toString() as config.yaml. JSON is only a
        // subset of YAML until JSON-only escaping (for example \/) appears.
        // SafeYaml emits YAML-defined escapes and preserves literal backslashes.
        return SafeYaml.dump(profile);
    }

    private static JSONObject findProxyOutbound(JSONObject source) throws Exception {
        JSONArray outbounds = source.optJSONArray("outbounds");
        if (outbounds == null) throw new IllegalArgumentException("节点配置缺少 outbound");
        for (int i = 0; i < outbounds.length(); i++) {
            JSONObject item = outbounds.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "").toLowerCase(Locale.ROOT);
            if (!"direct".equals(type) && !"block".equals(type) && !type.isEmpty()) return item;
        }
        throw new IllegalArgumentException("节点配置中没有可用代理");
    }

    private static JSONObject convertProxy(JSONObject src) throws Exception {
        String type = src.optString("type", "").toLowerCase(Locale.ROOT);
        if ("shadowsocks".equals(type)) type = "ss";
        if ("hy2".equals(type)) type = "hysteria2";

        JSONObject out = new JSONObject()
                .put("name", PROXY_NAME)
                .put("type", type)
                .put("server", require(src, "server", "节点缺少服务器地址"))
                .put("port", readPort(src))
                .put("udp", true);

        switch (type) {
            case "vless":
                out.put("uuid", require(src, "uuid", "VLESS 缺少 UUID"));
                copyIf(src, out, "flow", "flow");
                copyIf(src, out, "packet_encoding", "packet-encoding");
                applyTls(src, out, true, false);
                applyTransport(src, out);
                break;
            case "vmess":
                out.put("uuid", require(src, "uuid", "VMess 缺少 UUID"));
                out.put("alterId", src.optInt("alter_id", 0));
                out.put("cipher", first(src.optString("security", ""), "auto"));
                applyTls(src, out, true, false);
                applyTransport(src, out);
                break;
            case "trojan":
                out.put("password", require(src, "password", "Trojan 缺少密码"));
                applyTls(src, out, false, true);
                applyTransport(src, out);
                break;
            case "ss":
                out.put("cipher", require(src, "method", "Shadowsocks 缺少加密方式"));
                out.put("password", require(src, "password", "Shadowsocks 缺少密码"));
                copyIf(src, out, "plugin", "plugin");
                String pluginOpts = src.optString("plugin_opts", "").trim();
                if (!pluginOpts.isEmpty()) out.put("plugin-opts", parsePluginOptions(pluginOpts));
                break;
            case "hysteria2":
                out.put("password", require(src, "password", "Hysteria2 缺少密码"));
                if (src.has("up_mbps")) out.put("up", src.optInt("up_mbps"));
                if (src.has("down_mbps")) out.put("down", src.optInt("down_mbps"));
                JSONArray serverPorts = src.optJSONArray("server_ports");
                if (serverPorts != null && serverPorts.length() > 0) out.put("ports", serverPorts.optString(0));
                JSONObject obfs = src.optJSONObject("obfs");
                if (obfs != null) {
                    copyIf(obfs, out, "type", "obfs");
                    copyIf(obfs, out, "password", "obfs-password");
                }
                applyTls(src, out, false, true);
                break;
            case "tuic":
                out.put("uuid", require(src, "uuid", "TUIC 缺少 UUID"));
                out.put("password", require(src, "password", "TUIC 缺少密码"));
                copyIf(src, out, "congestion_control", "congestion-controller");
                copyIf(src, out, "udp_relay_mode", "udp-relay-mode");
                if (src.has("zero_rtt_handshake")) out.put("reduce-rtt", src.optBoolean("zero_rtt_handshake"));
                applyTls(src, out, false, true);
                break;
            case "anytls":
                out.put("password", require(src, "password", "AnyTLS 缺少密码"));
                copyIf(src, out, "idle_session_check_interval", "idle-session-check-interval");
                copyIf(src, out, "idle_session_timeout", "idle-session-timeout");
                if (src.has("min_idle_session")) out.put("min-idle-session", src.optInt("min_idle_session"));
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

        JSONObject utls = tls.optJSONObject("utls");
        if (utls != null && utls.optBoolean("enabled", true)) {
            String fingerprint = utls.optString("fingerprint", "").trim();
            if (!fingerprint.isEmpty()) out.put("client-fingerprint", fingerprint);
        }

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
                JSONObject headers = transport.optJSONObject("headers");
                if (headers != null && headers.length() > 0) opts.put("headers", headers);
                if (transport.has("max_early_data")) opts.put("max-early-data", transport.optInt("max_early_data"));
                copyIf(transport, opts, "early_data_header_name", "early-data-header-name");
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
                JSONArray hosts = transport.optJSONArray("host");
                if (hosts != null && hosts.length() > 0) opts.put("host", hosts);
                out.put("h2-opts", opts);
                return;
            }
            case "quic":
                out.put("network", "quic");
                return;
            case "":
                return;
            default:
                throw new IllegalArgumentException("Mihomo 暂不支持传输类型：" + type);
        }
    }

    private static JSONObject buildDns() throws Exception {
        return new JSONObject()
                .put("enable", true)
                .put("ipv6", true)
                .put("use-hosts", true)
                .put("respect-rules", true)
                .put("enhanced-mode", "fake-ip")
                .put("fake-ip-range", "198.18.0.1/16")
                .put("fake-ip-filter", new JSONArray().put("*.lan").put("*.local").put("localhost"))
                .put("default-nameserver", new JSONArray().put("223.5.5.5").put("119.29.29.29"))
                .put("proxy-server-nameserver", new JSONArray().put("223.5.5.5").put("119.29.29.29"))
                .put("nameserver", new JSONArray()
                        .put("https://1.1.1.1/dns-query")
                        .put("https://8.8.8.8/dns-query"));
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
                .put("IP-CIDR,127.0.0.0/8,DIRECT,no-resolve")
                .put("IP-CIDR,10.0.0.0/8,DIRECT,no-resolve")
                .put("IP-CIDR,172.16.0.0/12,DIRECT,no-resolve")
                .put("IP-CIDR,192.168.0.0/16,DIRECT,no-resolve")
                .put("IP-CIDR,169.254.0.0/16,DIRECT,no-resolve")
                .put("IP-CIDR6,::1/128,DIRECT,no-resolve")
                .put("IP-CIDR6,fc00::/7,DIRECT,no-resolve")
                .put("IP-CIDR6,fe80::/10,DIRECT,no-resolve");
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
        int port = src.optInt("server_port", 0);
        if (port <= 0) {
            JSONArray ports = src.optJSONArray("server_ports");
            if (ports != null && ports.length() > 0) port = firstPort(ports.optString(0));
        }
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("节点端口无效");
        return port;
    }

    private static int firstPort(String value) {
        String text = value == null ? "" : value.trim();
        int split = text.indexOf(':');
        if (split < 0) split = text.indexOf('-');
        if (split > 0) text = text.substring(0, split);
        try { return Integer.parseInt(text); }
        catch (Exception ignored) { return 0; }
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
}
