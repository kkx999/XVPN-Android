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

    @Test public void smartModeUsesSplitDnsPolicy() throws Exception {
        String yaml = MihomoProfileBuilder.build(vlessSource(), RouteMode.SMART.label);
        assertTrue(yaml.contains("\"nameserver-policy\""));
        assertTrue(yaml.contains("rule-set:XVPN-CN-DOMAIN"));
        assertTrue(yaml.contains("https://dns.alidns.com/dns-query"));
        assertTrue(yaml.contains("https://doh.pub/dns-query"));
        assertTrue(yaml.contains("https://1.1.1.1/dns-query"));
        assertTrue(yaml.contains("https://8.8.8.8/dns-query"));
        assertTrue(yaml.contains("\"direct-nameserver-follow-policy\": true"));
    }

    @Test public void globalModeDoesNotLoadCnProvidersOrCnDnsPolicy() throws Exception {
        String yaml = MihomoProfileBuilder.build(vlessSource(), RouteMode.GLOBAL.label);
        assertFalse(yaml.contains("\"rule-providers\""));
        assertFalse(yaml.contains("RULE-SET,XVPN-CN-DOMAIN,DIRECT"));
        assertFalse(yaml.contains("RULE-SET,XVPN-CN-IP,DIRECT,no-resolve"));
        assertFalse(yaml.contains("\"nameserver-policy\""));
        assertTrue(yaml.contains("MATCH,XVPN"));
    }

    @Test public void localAndSpecialRangesRemainDirectInBothModes() throws Exception {
        String smart = MihomoProfileBuilder.build(vlessSource(), RouteMode.SMART.label);
        String global = MihomoProfileBuilder.build(vlessSource(), RouteMode.GLOBAL.label);
        for (String rule : new String[]{
                "DOMAIN,localhost,DIRECT",
                "DOMAIN-SUFFIX,local,DIRECT",
                "DOMAIN-SUFFIX,lan,DIRECT",
                "DOMAIN-SUFFIX,home.arpa,DIRECT",
                "IP-CIDR,100.64.0.0/10,DIRECT,no-resolve",
                "IP-CIDR,224.0.0.0/4,DIRECT,no-resolve",
                "IP-CIDR6,ff00::/8,DIRECT,no-resolve"}) {
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
