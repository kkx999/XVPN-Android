package com.xvpn.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class NodeCatalogTest {
    @Test public void parsesOnlyPanelV1ProfileObjects() throws Exception {
        JSONObject profile = new JSONObject()
                .put("schema", "xvpn.node.v1")
                .put("protocol", "trojan")
                .put("server", "hk.example.com")
                .put("port", 443)
                .put("auth", new JSONObject().put("password", "secret"))
                .put("tls", new JSONObject().put("enabled", true))
                .put("transport", new JSONObject().put("type", "tcp"))
                .put("options", new JSONObject());
        JSONObject node = new JSONObject()
                .put("id", 9)
                .put("name", "香港01")
                .put("display_name", "香港01")
                .put("country", "香港")
                .put("country_code", "HK")
                .put("protocol", "trojan")
                .put("profile", profile);
        JSONObject country = new JSONObject()
                .put("country", "香港")
                .put("country_code", "HK")
                .put("flag_emoji", "🇭🇰")
                .put("nodes", new JSONArray().put(node));
        JSONObject nodes = new JSONObject()
                .put("schema", "xvpn.nodes.v1")
                .put("node_schema", "xvpn.node.v1")
                .put("core", "mihomo")
                .put("total", 1)
                .put("countries", new JSONArray().put(country));
        JSONObject bootstrap = new JSONObject()
                .put("ok", true)
                .put("api", "v1")
                .put("core", "mihomo")
                .put("node_schema", "xvpn.node.v1")
                .put("nodes", nodes);

        NodeCatalog catalog = NodeCatalog.fromBootstrap(bootstrap);
        assertEquals(1, catalog.countries.size());
        NodeCatalog.Node parsed = catalog.find(9);
        assertNotNull(parsed);
        assertEquals("hk.example.com", parsed.server);
        assertEquals(443, parsed.port);
        assertEquals("trojan", parsed.protocol);
        assertEquals("xvpn.node.v1", parsed.profile.getString("schema"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLegacyCatalogWithoutSchema() {
        NodeCatalog.fromBootstrap(new JSONObject()
                .put("api", "v1")
                .put("core", "mihomo")
                .put("node_schema", "xvpn.node.v1")
                .put("nodes", new JSONObject().put("countries", new JSONArray())));
    }
}
