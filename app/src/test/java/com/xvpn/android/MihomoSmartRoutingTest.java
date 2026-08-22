package com.xvpn.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MihomoSmartRoutingTest {
    private static String vlessSource() throws Exception {
        JSONObject proxy = new JSONObject()
                .put("type", "vless")
                .put("server", "example.com")
                .put("server_port", 443)
                .put("uuid", "11111111-2222-3333-4444-555555555555");
        return new JSONObject()
                .put("outbounds", new JSONArray()
                        .put(proxy)
                        .put(new JSONObject().put("type", "direct"))
                        .put(new JSONObject().put("type", "block")))
                .toString();
    }

    @Test public void smartModeUsesBundledCnDomainAndIpRules() throws Exception {
        String yaml = MihomoProfileBuilder.build(vlessSource(), RouteMode.SMART.label);
        assertTrue(yaml.contains("\"rule-providers\""));
        assertTrue(yaml.contains("\"XVPN-CN-DOMAIN\""));
        assertTrue(yaml.contains("\"XVPN-CN-IP\""));
        assertTrue(yaml.contains("\"./rules/geosite-cn.mrs\""));
        assertTrue(yaml.contains("\"./rules/geoip-cn.mrs\""));
        assertTrue(yaml.contains("RULE-SET,XVPN-CN-DOMAIN,DIRECT"));
        assertTrue(yaml.contains("RULE-SET,XVPN-CN-IP,DIRECT,no-resolve"));
        assertTrue(yaml.contains("MATCH,XVPN"));
    }

    @Test public void globalModeDoesNotLoadCnProviders() throws Exception {
        String yaml = MihomoProfileBuilder.build(vlessSource(), RouteMode.GLOBAL.label);
        assertFalse(yaml.contains("\"rule-providers\""));
        assertFalse(yaml.contains("RULE-SET,XVPN-CN-DOMAIN,DIRECT"));
        assertFalse(yaml.contains("RULE-SET,XVPN-CN-IP,DIRECT,no-resolve"));
        assertTrue(yaml.contains("MATCH,XVPN"));
    }

    @Test public void localNamesRemainDirectInBothModes() throws Exception {
        String smart = MihomoProfileBuilder.build(vlessSource(), RouteMode.SMART.label);
        String global = MihomoProfileBuilder.build(vlessSource(), RouteMode.GLOBAL.label);
        for (String rule : new String[]{
                "DOMAIN,localhost,DIRECT",
                "DOMAIN-SUFFIX,local,DIRECT",
                "DOMAIN-SUFFIX,lan,DIRECT",
                "DOMAIN-SUFFIX,home.arpa,DIRECT"}) {
            assertTrue(smart.contains(rule));
            assertTrue(global.contains(rule));
        }
    }

    @Test public void auxiliaryOutboundCannotBeMistakenForProxy() throws Exception {
        JSONObject source = new JSONObject().put("outbounds", new JSONArray()
                .put(new JSONObject().put("type", "dns"))
                .put(new JSONObject()
                        .put("type", "trojan")
                        .put("server", "example.com")
                        .put("server_port", 443)
                        .put("password", "secret")
                        .put("tls", new JSONObject().put("enabled", true).put("server_name", "example.com"))));
        String yaml = MihomoProfileBuilder.build(source.toString(), RouteMode.SMART.label);
        assertTrue(yaml.contains("\"type\": \"trojan\""));
        assertFalse(yaml.contains("\"type\": \"dns\""));
    }
}
