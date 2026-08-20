package com.xvpn.android;

import org.json.JSONObject;

import java.util.Locale;

/** Conservative public-exit classification used by the connected home card. */
final class ExitIpClassifier {
    private ExitIpClassifier() {}

    static Info fromIpApi(JSONObject root, String expectedCountryCode) {
        if (root == null || root.has("error")) return null;
        String ip = value(root.optString("ip", ""));
        if (ip.isEmpty()) return null;
        String provider = first(root.optString("company_name", ""), root.optString("asn_org", ""));
        String countryCode = value(root.optString("cc", "")).toUpperCase(Locale.ROOT);
        Boolean hosting = nullableBoolean(root, "is_datacenter");
        return build(ip, provider, countryCode, expectedCountryCode, hosting);
    }

    static Info fromIpWho(JSONObject root, String expectedCountryCode) {
        if (root == null || !root.optBoolean("success", true)) return null;
        String ip = value(root.optString("ip", ""));
        if (ip.isEmpty()) return null;
        JSONObject connection = root.optJSONObject("connection");
        String provider = connection == null ? "" : first(connection.optString("isp", ""), connection.optString("org", ""));
        String countryCode = value(root.optString("country_code", "")).toUpperCase(Locale.ROOT);
        JSONObject security = root.optJSONObject("security");
        Boolean hosting = security == null ? null : nullableBoolean(security, "hosting");
        return build(ip, provider, countryCode, expectedCountryCode, hosting);
    }

    private static Info build(String ip, String provider, String countryCode,
                              String expectedCountryCode, Boolean hostingSignal) {
        boolean providerLooksHosting = looksLikeHosting(provider);
        String type;
        if (Boolean.TRUE.equals(hostingSignal) || providerLooksHosting) type = "机房 IP";
        else if (Boolean.FALSE.equals(hostingSignal)) type = "家宽 / 运营商 IP";
        else type = "类型待确认";

        String expected = value(expectedCountryCode).toUpperCase(Locale.ROOT);
        boolean comparable = !expected.isEmpty() && !"ZZ".equals(expected) && !countryCode.isEmpty();
        boolean countryMatch = comparable && expected.equals(countryCode);
        String regionLabel = !comparable ? "地区待确认"
                : countryMatch ? ("机房 IP".equals(type) ? "地区匹配" : "原生地区候选")
                : "地区不一致";
        return new Info(ip, type, provider.isEmpty() ? "运营商信息待确认" : provider,
                countryCode, regionLabel, countryMatch);
    }

    private static Boolean nullableBoolean(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) return null;
        Object raw = object.opt(key);
        if (raw instanceof Boolean) return (Boolean) raw;
        String text = value(String.valueOf(raw));
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) return Boolean.FALSE;
        return null;
    }

    private static boolean looksLikeHosting(String provider) {
        String lower = value(provider).toLowerCase(Locale.ROOT);
        String[] hints = {"cloud", "hosting", "host", "server", "data center", "datacenter",
                "vps", "colo", "digitalocean", "amazon", "google cloud", "microsoft azure",
                "oracle", "alibaba cloud", "tencent cloud", "linode", "vultr"};
        for (String hint : hints) if (lower.contains(hint)) return true;
        return false;
    }

    private static String first(String first, String second) {
        String a = value(first);
        return a.isEmpty() ? value(second) : a;
    }

    private static String value(String text) { return text == null ? "" : text.trim(); }

    static final class Info {
        final String ip;
        final String typeLabel;
        final String provider;
        final String countryCode;
        final String regionLabel;
        final boolean countryMatch;

        Info(String ip, String typeLabel, String provider, String countryCode,
             String regionLabel, boolean countryMatch) {
            this.ip = value(ip);
            this.typeLabel = value(typeLabel);
            this.provider = value(provider);
            this.countryCode = value(countryCode);
            this.regionLabel = value(regionLabel);
            this.countryMatch = countryMatch;
        }
    }
}
