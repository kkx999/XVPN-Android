package com.xvpn.android;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MihomoProfileBuilderTest {
    private static NodeCatalog.Node node(String protocol) throws Exception {
        JSONObject auth = new JSONObject();
        switch (protocol) {
            case "vless":
            case "vmess":
                auth.put("uuid", "11111111-2222-3333-4444-555555555555");
                if ("vmess".equals(protocol)) auth.put("alter_id", 0);
                break;
            case "trojan":
            case "hysteria2":
            case "anytls":
                auth.put("password", "secret");
                break;
            case "shadowsocks":
                auth.put("method", "aes-128-gcm").put("password", "secret");
                break;
            case "tuic":
                auth.put("uuid", "11111111-2222-3333-4444-555555555555").put("password", "secret");
                break;
            default:
                throw new IllegalArgumentException(protocol);
        }
        boolean requiredTls = "hysteria2".equals(protocol) || "tuic".equals(protocol) || "anytls".equals(protocol);
        JSONObject profile = new JSONObject()
                .put("schema", "xvpn.node.v1")
                .put("protocol", protocol)
                .put("server", "edge.example.com")
                .put("port", 443)
                .put("auth", auth)
                .put("tls", new JSONObject().put("enabled", requiredTls))
                .put("transport", new JSONObject().put("type", "tcp"))
                .put("options", new JSONObject());
        NodeCatalog.Node node = new NodeCatalog.Node();
        node.id = 7;
        node.server = "edge.example.com";
        node.port = 443;
        node.protocol = protocol;
        node.profile = profile;
        return node;
    }

    @Test public void allPanelV1ProtocolsBuildMihomoYaml() throws Exception {
        for (String protocol : new String[]{
                "vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "anytls"}) {
            String yaml = MihomoProfileBuilder.build(null, node(protocol), RouteMode.SMART);
            String mihomoType = "shadowsocks".equals(protocol) ? "ss" : protocol;
            assertTrue(protocol, yaml.contains("\"type\": \"" + mihomoType + "\""));
            assertTrue(protocol, yaml.contains("\"server\": \"edge.example.com\""));
        }
    }

    @Test public void realityAndWebSocketMapFromStandardProfile() throws Exception {
        NodeCatalog.Node node = node("vless");
        node.profile.getJSONObject("tls")
                .put("enabled", true)
                .put("server_name", "origin.example.com")
                .put("fingerprint", "chrome")
                .put("reality", new JSONObject()
                        .put("enabled", true)
                        .put("public_key", "public-key")
                        .put("short_id", "abcd"));
        node.profile.put("transport", new JSONObject()
                .put("type", "ws")
                .put("path", "/xvpn")
                .put("host", "cdn.example.com"));
        node.profile.getJSONObject("options").put("flow", "xtls-rprx-vision");

        String yaml = MihomoProfileBuilder.build(null, node, RouteMode.SMART);
        assertTrue(yaml.contains("\"client-fingerprint\": \"chrome\""));
        assertTrue(yaml.contains("\"reality-opts\""));
        assertTrue(yaml.contains("\"network\": \"ws\""));
        assertTrue(yaml.contains("\"Host\": \"cdn.example.com\""));
        assertTrue(yaml.contains("\"flow\": \"xtls-rprx-vision\""));
    }

    @Test public void smartAndGlobalRoutingRemainDistinct() throws Exception {
        String smart = MihomoProfileBuilder.build(null, node("vless"), RouteMode.SMART);
        String global = MihomoProfileBuilder.build(null, node("vless"), RouteMode.GLOBAL);
        assertTrue(smart.contains("\"rule-providers\""));
        assertTrue(smart.contains("RULE-SET,XVPN-CN-DOMAIN,DIRECT"));
        assertTrue(smart.contains("RULE-SET,XVPN-CN-IP,DIRECT,no-resolve"));
        assertFalse(global.contains("\"rule-providers\""));
        assertFalse(global.contains("RULE-SET,XVPN-CN-DOMAIN,DIRECT"));
        assertTrue(global.contains("IP-CIDR,192.168.0.0/16,DIRECT,no-resolve"));
        assertTrue(global.contains("MATCH,XVPN"));
    }

    @Test public void latencyEndpointUsesStandardServerAndProtocol() throws Exception {
        NodeCatalog.Node tcp = node("trojan");
        MihomoProfileBuilder.Endpoint endpoint = MihomoProfileBuilder.endpoint(tcp);
        assertTrue(endpoint.tcpProbeSupported);
        assertTrue(endpoint.port == 443);
        assertTrue("edge.example.com".equals(endpoint.host));

        assertFalse(MihomoProfileBuilder.endpoint(node("hysteria2")).tcpProbeSupported);
        assertFalse(MihomoProfileBuilder.endpoint(node("tuic")).tcpProbeSupported);
    }
}
