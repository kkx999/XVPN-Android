package com.xvpn.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SafeYamlTest {
    @Test
    public void yamlQuoteEscapesBackslashWithoutJsonSlashEscape() {
        String quoted = SafeYaml.quote("a\\b/c\"d\n#x:y");
        assertTrue(quoted.startsWith("\"") && quoted.endsWith("\""));
        assertTrue(quoted.contains("a\\\\b/c\\\"d\\n#x:y"));
        assertFalse(quoted.contains("\\/"));
    }

    @Test
    public void builderProducesYamlSafeForSpecialTransportValues() throws Exception {
        JSONObject outbound = new JSONObject()
                .put("type", "vless")
                .put("server", "example.com")
                .put("server_port", 443)
                .put("uuid", "11111111-2222-3333-4444-555555555555")
                .put("flow", "xtls-rprx-vision")
                .put("tls", new JSONObject()
                        .put("enabled", true)
                        .put("server_name", "edge.example.com")
                        .put("utls", new JSONObject().put("enabled", true).put("fingerprint", "chrome")))
                .put("transport", new JSONObject()
                        .put("type", "ws")
                        .put("path", "/a\\b/</script>#x:y?z=\"1\"")
                        .put("headers", new JSONObject().put("Host", "cdn.example.com")));

        String source = new JSONObject()
                .put("outbounds", new JSONArray().put(outbound).put(new JSONObject().put("type", "direct")))
                .toString();

        String yaml = MihomoProfileBuilder.build(source, "智能分流");
        assertTrue(yaml.contains("\"type\": \"vless\""));
        assertTrue(yaml.contains("\"network\": \"ws\""));
        assertTrue(yaml.contains("/a\\\\b/</script>#x:y?z=\\\"1\\\""));
        assertFalse(yaml.contains("\\/"));
        assertFalse(yaml.trim().startsWith("{"));
    }

    @Test
    public void trojanPasswordCannotBreakYaml() throws Exception {
        String password = "pa\\ss:\"word#[]{};'/</script>";
        JSONObject outbound = new JSONObject()
                .put("type", "trojan")
                .put("server", "1.2.3.4")
                .put("server_port", 443)
                .put("password", password)
                .put("tls", new JSONObject().put("enabled", true).put("server_name", "example.com"));
        String source = new JSONObject().put("outbounds", new JSONArray().put(outbound)).toString();
        String yaml = MihomoProfileBuilder.build(source, "全局模式");
        assertTrue(yaml.contains("pa\\\\ss:\\\"word#[]{};'"));
        assertFalse(yaml.contains("\\/"));
    }
}
