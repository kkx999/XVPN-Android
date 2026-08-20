package com.xvpn.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class SingBoxConfigBuilderTest {
    private static final String UUID = "11111111-1111-4111-8111-111111111111";
    private static final RuleSetInstaller.Paths RULES =
            new RuleSetInstaller.Paths("/rules/geosite-cn.srs", "/rules/geoip-cn.srs");

    @Test public void allSupportedProtocolsBuildInSmartAndGlobalModes() throws Exception {
        for (Sample sample : samples()) {
            for (RouteMode mode : RouteMode.values()) {
                NodeCatalog.Node node = new NodeCatalog.Node();
                node.id = 1;
                node.name = sample.type;
                node.protocol = sample.type;
                node.config = sample.uri;

                JSONObject root = SingBoxConfigBuilder.build(node, mode, RULES);
                JSONObject proxy = root.getJSONArray("outbounds").getJSONObject(0);
                assertEquals(sample.type, proxy.getString("type"));
                assertEquals("proxy", proxy.getString("tag"));
                assertEquals("example.com", proxy.getString("server"));
                assertEquals(443, SingBoxConfigBuilder.endpoint(node).port);
                assertBaseNetworkProfile(root);
                assertRoutingMode(root, mode);
            }
        }
    }

    @Test public void panelBaseNormalizesApiSuffixWithoutChangingHost() {
        assertEquals(ApiClient.DEFAULT_PANEL_BASE,
                ApiClient.normalizePanelBase(ApiClient.DEFAULT_PANEL_BASE + "/api/v1/"));
        assertEquals("https://panel.example.com",
                ApiClient.normalizePanelBase("https://panel.example.com/"));
    }

    @Test public void stableReleaseWinsOverMatchingPrerelease() {
        assertTrue(AppUpdateChecker.compareVersions("1.0.0", "1.0.0-rc1") > 0);
        assertTrue(AppUpdateChecker.compareVersions("1.0.0-rc6", "1.0.0-rc5") > 0);
        assertTrue(AppUpdateChecker.compareVersions("1.0.1", "1.0.0") > 0);
        assertEquals(0, AppUpdateChecker.compareVersions("v1.0.0", "1.0.0"));
    }

    @Test public void rawHtmlServerFailureIsNeverShownToTheUser() {
        assertEquals("服务器暂时异常，请稍后重试",
                ApiClient.safeServerMessage(500, "<!doctype html><html><body>internal error</body></html>"));
        assertEquals("接口地址不存在，请检查 Panel 配置", ApiClient.safeServerMessage(404, ""));
        assertEquals("账户被暂停", ApiClient.safeServerMessage(400, "账户被暂停"));
    }

    @Test public void phoneUiScaleShrinksOnlyWhenSpaceIsTight() {
        assertEquals(1f, UiScalePolicy.layoutScale(411, 820), .001f);
        assertTrue(UiScalePolicy.layoutScale(360, 800) < 1f);
        assertEquals(.86f, UiScalePolicy.layoutScale(320, 700), .001f);
        assertTrue(UiScalePolicy.textScale(UiScalePolicy.layoutScale(360, 800), 1.3f) < 1f);
    }

    @Test public void savedLatencyHostAlwaysUsesProxyAndSecureDns() throws Exception {
        NodeCatalog.Node node = new NodeCatalog.Node();
        node.id = 1;
        node.name = "VLESS";
        node.protocol = "vless";
        node.config = "vless://" + UUID
                + "@example.com:443?security=tls&sni=example.com&type=ws&host=example.com&path=%2Fws";
        JSONObject root = SingBoxConfigBuilder.build(node, RouteMode.SMART, RULES, "www.apple.com");
        JSONObject dnsRule = findDomainRule(root.getJSONObject("dns").getJSONArray("rules"), "www.apple.com");
        JSONObject routeRule = findDomainRule(root.getJSONObject("route").getJSONArray("rules"), "www.apple.com");
        assertNotNull(dnsRule);
        assertNotNull(routeRule);
        assertEquals("secure-dns", dnsRule.getString("server"));
        assertEquals("proxy", routeRule.getString("outbound"));
    }

    @Test public void exitIpClassificationIsConservative() throws Exception {
        ExitIpClassifier.Info hosting = ExitIpClassifier.fromIpApi(new JSONObject()
                .put("ip", "203.0.113.8").put("is_datacenter", true)
                .put("company_name", "Example Cloud").put("cc", "JP"), "JP");
        assertNotNull(hosting);
        assertEquals("机房 IP", hosting.typeLabel);
        assertEquals("地区匹配", hosting.regionLabel);

        ExitIpClassifier.Info residential = ExitIpClassifier.fromIpApi(new JSONObject()
                .put("ip", "198.51.100.9").put("is_datacenter", false)
                .put("asn_org", "Example Broadband ISP").put("cc", "TW"), "TW");
        assertNotNull(residential);
        assertEquals("家宽 / 运营商 IP", residential.typeLabel);
        assertEquals("原生地区候选", residential.regionLabel);
    }

    private static void assertBaseNetworkProfile(JSONObject root) throws Exception {
        JSONObject tun = root.getJSONArray("inbounds").getJSONObject(0);
        assertEquals("tun", tun.getString("type"));
        assertEquals(1400, tun.getInt("mtu"));
        assertEquals("mixed", tun.getString("stack"));
        assertEquals(1, tun.getJSONArray("address").length());
        assertTrue(tun.getJSONArray("address").getString(0).contains("172.19.0.1/30"));

        JSONObject dns = root.getJSONObject("dns");
        assertEquals("ipv4_only", dns.getString("strategy"));
        JSONArray servers = dns.getJSONArray("servers");
        assertEquals("local-dns", servers.getJSONObject(0).getString("tag"));
        assertEquals("udp", servers.getJSONObject(0).getString("type"));
        assertEquals("223.5.5.5", servers.getJSONObject(0).getString("server"));
        assertFalse(servers.getJSONObject(0).has("detour"));
        assertFalse(servers.getJSONObject(0).has("tls"));
        assertEquals("tcp", servers.getJSONObject(1).getString("type"));
        assertEquals("8.8.8.8", servers.getJSONObject(1).getString("server"));
        assertEquals("proxy", servers.getJSONObject(1).getString("detour"));
        assertFalse(servers.getJSONObject(1).has("tls"));
        assertEquals("local-dns", root.getJSONObject("route").getString("default_domain_resolver"));

        JSONObject quicReject = findRule(root.getJSONObject("route").getJSONArray("rules"),
                "network", "udp");
        assertNotNull(quicReject);
        assertEquals(443, quicReject.getInt("port"));
        assertEquals("reject", quicReject.getString("action"));
    }

    private static void assertRoutingMode(JSONObject root, RouteMode mode) throws Exception {
        JSONObject route = root.getJSONObject("route");
        assertEquals("proxy", route.getString("final"));
        JSONArray rules = route.getJSONArray("rules");
        boolean hasGeosite = hasRuleSet(rules, "geosite-cn");
        boolean hasGeoip = hasRuleSet(rules, "geoip-cn");
        if (mode == RouteMode.SMART) {
            assertTrue(hasGeosite);
            assertTrue(hasGeoip);
        } else {
            assertFalse(hasGeosite);
            assertFalse(hasGeoip);
        }
    }

    private static JSONObject findRule(JSONArray rules, String key, String value) throws Exception {
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            if (value.equals(rule.optString(key))) return rule;
        }
        return null;
    }

    private static JSONObject findDomainRule(JSONArray rules, String domain) throws Exception {
        for (int i = 0; i < rules.length(); i++) {
            JSONArray values = rules.getJSONObject(i).optJSONArray("domain");
            if (values == null) continue;
            for (int j = 0; j < values.length(); j++) {
                if (domain.equals(values.getString(j))) return rules.getJSONObject(i);
            }
        }
        return null;
    }

    private static boolean hasRuleSet(JSONArray rules, String tag) throws Exception {
        for (int i = 0; i < rules.length(); i++) {
            JSONArray values = rules.getJSONObject(i).optJSONArray("rule_set");
            if (values == null) continue;
            for (int j = 0; j < values.length(); j++) if (tag.equals(values.getString(j))) return true;
        }
        return false;
    }

    private static List<Sample> samples() throws Exception {
        List<Sample> values = new ArrayList<>();
        values.add(new Sample("vless", "vless://" + UUID
                + "@example.com:443?security=tls&sni=example.com&type=ws&host=example.com&path=%2Fws"));
        values.add(new Sample("trojan",
                "trojan://password@example.com:443?security=tls&sni=example.com&type=grpc&serviceName=xvpn"));

        String vmess = new JSONObject()
                .put("v", "2").put("add", "example.com").put("port", "443")
                .put("id", UUID).put("aid", "0").put("scy", "auto")
                .put("net", "ws").put("host", "example.com").put("path", "/ws")
                .put("tls", "tls").put("sni", "example.com").toString();
        values.add(new Sample("vmess", "vmess://" + Base64.getEncoder()
                .encodeToString(vmess.getBytes(StandardCharsets.UTF_8))));

        values.add(new Sample("shadowsocks",
                "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@example.com:443#XVPN"));
        values.add(new Sample("hysteria2",
                "hysteria2://password@example.com:443?sni=example.com&insecure=1&obfs=salamander&obfs-password=test"));
        values.add(new Sample("tuic", "tuic://" + UUID
                + ":password@example.com:443?sni=example.com&allow_insecure=1&congestion_control=bbr"));
        values.add(new Sample("anytls",
                "anytls://password@example.com:443?sni=example.com&insecure=1"));
        return values;
    }

    private static final class Sample {
        final String type;
        final String uri;
        Sample(String type, String uri) { this.type = type; this.uri = uri; }
    }
}
