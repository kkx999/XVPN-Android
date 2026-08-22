from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else '.')

def once(s, old, new, label):
    if new in s:
        return s
    if old not in s:
        raise RuntimeError(f'{label} anchor missing')
    return s.replace(old, new, 1)

# Existing UI/interaction/latency code is frozen. Only verify the requested
# Mihomo version string is already present in the existing version row.
ui = (root / 'app/src/main/java/com/xvpn/android/MainActivity.java').read_text()
if 'BuildConfig.MIHOMO_CORE_VERSION' not in ui:
    raise RuntimeError('Mihomo version row missing')

p = root / 'app/src/main/java/com/xvpn/android/VpnCoreService.java'
s = p.read_text()
if 'import android.net.NetworkRequest;' not in s:
    s = once(s,
        'import android.net.NetworkCapabilities;\nimport android.net.VpnService;',
        'import android.net.NetworkCapabilities;\nimport android.net.NetworkRequest;\nimport android.net.VpnService;',
        'NetworkRequest')
if 'private ConnectivityManager.NetworkCallback underlyingNetworkCallback;' not in s:
    s = once(s,
        '    private ConnectivityManager connectivity;\n    private ParcelFileDescriptor tunDescriptor;',
        '    private ConnectivityManager connectivity;\n    private ConnectivityManager.NetworkCallback underlyingNetworkCallback;\n    private ParcelFileDescriptor tunDescriptor;',
        'network callback field')
if 'registerUnderlyingNetworkObserver();' not in s:
    s = once(s,
        '        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);\n        createNotificationChannels();',
        '        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);\n        registerUnderlyingNetworkObserver();\n        createNotificationChannels();',
        'register network observer')
if 'unregisterUnderlyingNetworkObserver();' not in s:
    s = once(s,
        '        stopRequested.set(true);\n        shutdownSchedulers(false);',
        '        stopRequested.set(true);\n        unregisterUnderlyingNetworkObserver();\n        shutdownSchedulers(false);',
        'unregister network observer')
if 'builder.setMetered(false);' not in s:
    s = once(s,
        '                .addDnsServer("fdfe:dcba:9876::2")\n                .setBlocking(true);',
        '                .addDnsServer("fdfe:dcba:9876::2")\n                .setBlocking(true);\n        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false);',
        'setMetered')
s = once(s,
    '                "172.19.0.1/30,fdfe:dcba:9876::1/126",\n                "172.19.0.2,fdfe:dcba:9876::2",\n                MihomoProfileBuilder.TUN_MTU);',
    '                "172.19.0.1/30,fdfe:dcba:9876::1/126",\n                "0.0.0.0,::",\n                MihomoProfileBuilder.TUN_MTU);',
    'DNS hijack')
# Replace only the post-establish pin. The pre-establish Builder binding remains.
s = s.replace('        if (physical != null) setUnderlyingNetworks(new Network[]{physical});\n',
              '        refreshUnderlyingNetwork();\n', 1)
if 'private void registerUnderlyingNetworkObserver()' not in s:
    anchor = '    private Network physicalNetwork() {'
    if anchor not in s:
        raise RuntimeError('physicalNetwork anchor missing')
    helper = '''    private void registerUnderlyingNetworkObserver() {
        if (connectivity == null || underlyingNetworkCallback != null) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        underlyingNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { refreshUnderlyingNetwork(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { refreshUnderlyingNetwork(); }
            @Override public void onLost(Network network) { refreshUnderlyingNetwork(); }
        };
        try { connectivity.registerNetworkCallback(request, underlyingNetworkCallback); }
        catch (Exception error) {
            Log.w(TAG, "Unable to observe physical network changes", error);
            underlyingNetworkCallback = null;
        }
    }

    private void unregisterUnderlyingNetworkObserver() {
        ConnectivityManager.NetworkCallback callback = underlyingNetworkCallback;
        underlyingNetworkCallback = null;
        if (connectivity != null && callback != null) {
            try { connectivity.unregisterNetworkCallback(callback); }
            catch (Exception ignored) {}
        }
    }

    private void refreshUnderlyingNetwork() {
        if (tunDescriptor == null) return;
        Network network = physicalNetwork();
        if (network == null) return;
        try { setUnderlyingNetworks(new Network[]{network}); }
        catch (Exception error) { Log.w(TAG, "Unable to update VPN underlying network", error); }
    }

'''
    s = s.replace(anchor, helper + anchor, 1)
p.write_text(s)

p = root / 'app/src/main/java/com/xvpn/android/MihomoProfileBuilder.java'
s = p.read_text()
s = once(s, '.put("dns", buildDns());', '.put("dns", buildDns(global));', 'buildDns call')
if 'private static JSONObject buildDns(boolean global)' not in s:
    start = s.index('    private static JSONObject buildDns() throws Exception {')
    end = s.index('\n    private static JSONObject buildRuleProviders()', start)
    new_dns = '''    private static JSONObject buildDns(boolean global) throws Exception {
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
'''
    s = s[:start] + new_dns + s[end:]
if 'IP-CIDR,100.64.0.0/10,DIRECT,no-resolve' not in s:
    s = once(s,
        '                .put("IP-CIDR,10.0.0.0/8,DIRECT,no-resolve")\n                .put("IP-CIDR,172.16.0.0/12,DIRECT,no-resolve")',
        '                .put("IP-CIDR,10.0.0.0/8,DIRECT,no-resolve")\n                .put("IP-CIDR,100.64.0.0/10,DIRECT,no-resolve")\n                .put("IP-CIDR,172.16.0.0/12,DIRECT,no-resolve")',
        'CGNAT')
if 'IP-CIDR,224.0.0.0/4,DIRECT,no-resolve' not in s:
    s = once(s,
        '                .put("IP-CIDR,169.254.0.0/16,DIRECT,no-resolve")\n                .put("IP-CIDR6,::1/128,DIRECT,no-resolve")',
        '                .put("IP-CIDR,169.254.0.0/16,DIRECT,no-resolve")\n                .put("IP-CIDR,224.0.0.0/4,DIRECT,no-resolve")\n                .put("IP-CIDR6,::1/128,DIRECT,no-resolve")',
        'IPv4 multicast')
if 'IP-CIDR6,ff00::/8,DIRECT,no-resolve' not in s:
    s = once(s,
        '                .put("IP-CIDR6,fc00::/7,DIRECT,no-resolve")\n                .put("IP-CIDR6,fe80::/10,DIRECT,no-resolve");',
        '                .put("IP-CIDR6,fc00::/7,DIRECT,no-resolve")\n                .put("IP-CIDR6,fe80::/10,DIRECT,no-resolve")\n                .put("IP-CIDR6,ff00::/8,DIRECT,no-resolve");',
        'IPv6 multicast')
p.write_text(s)

p = root / 'app/src/test/java/com/xvpn/android/MihomoSmartRoutingTest.java'
s = p.read_text()
if 'smartModeUsesSplitDnsPolicy' not in s:
    anchor = '\n    @Test public void auxiliaryOutboundCannotBeMistakenForProxy() throws Exception {'
    if anchor not in s:
        raise RuntimeError('smart routing test anchor missing')
    test = '''

    @Test public void smartModeUsesSplitDnsPolicy() throws Exception {
        String smart = MihomoProfileBuilder.build(vlessSource(), RouteMode.SMART.label);
        assertTrue(smart.contains("rule-set:XVPN-CN-DOMAIN"));
        assertTrue(smart.contains("https://dns.alidns.com/dns-query"));
        assertTrue(smart.contains("https://doh.pub/dns-query"));
        assertTrue(smart.contains("https://1.1.1.1/dns-query"));
        assertTrue(smart.contains("IP-CIDR,100.64.0.0/10,DIRECT,no-resolve"));
        assertTrue(smart.contains("IP-CIDR6,ff00::/8,DIRECT,no-resolve"));
        String global = MihomoProfileBuilder.build(vlessSource(), RouteMode.GLOBAL.label);
        assertFalse(global.contains("rule-set:XVPN-CN-DOMAIN"));
    }
'''
    s = s.replace(anchor, test + anchor, 1)
p.write_text(s)
