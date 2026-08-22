package com.xvpn.android;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import io.github.oviron.libmihomo.Clash;
import io.github.oviron.libmihomo.InvokeInterface;
import io.github.oviron.libmihomo.TunInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mihomo (Clash Meta) data plane for XVPN Android.
 *
 * The public/static surface intentionally matches the previous VpnCoreService
 * so MainActivity, Panel traffic reporting, Always-on restart and UI state do
 * not need to know which native core is underneath.
 */
public final class VpnCoreService extends VpnService {
    private static final String TAG = "XVPN-Mihomo";
    private static final String ACTION_START = "com.xvpn.android.action.START_CORE";
    private static final String ACTION_STOP = "com.xvpn.android.action.STOP_CORE";
    private static final String ACTION_RECONFIGURE = "com.xvpn.android.action.RECONFIGURE_CORE";
    private static final String EXTRA_CONFIG = "config";
    private static final String EXTRA_NODE_ID = "node_id";
    private static final String EXTRA_NODE_NAME = "node_name";
    private static final String EXTRA_ROUTE_LABEL = "route_label";
    private static final String EXTRA_HEALTH_TARGET = "health_target";
    private static final String APP_PREFS = "xvpn_preferences_v1";
    private static final String NOTIFICATION_CHANNEL = "xvpn_vpn_service";
    private static final int NOTIFICATION_ID = 51;
    private static final Object CORE_LOCK = new Object();
    private static volatile boolean mihomoReady;
    private static volatile boolean live;
    private static volatile VpnCoreService activeInstance;

    private final ExecutorService coreExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService reportIo = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean switchInProgress = new AtomicBoolean(false);
    private final AtomicBoolean versionBlockNotified = new AtomicBoolean(false);

    private ConnectivityManager connectivity;
    private ParcelFileDescriptor tunDescriptor;
    private volatile boolean tunStarted;
    private volatile int nodeId;
    private volatile String nodeName = "";
    private volatile String routeLabel = "智能分流";
    private volatile String sessionId = "";
    private volatile String activeConfig = "";
    private volatile String activeHealthTarget = "";
    private volatile TunnelHealth lastTunnelHealth = TunnelHealth.failure("尚未完成联网检测");

    private ScheduledExecutorService metricsExecutor;
    private ScheduledExecutorService reportExecutor;
    private long coreBaselineUp;
    private long coreBaselineDown;
    private volatile long uploadTotal;
    private volatile long downloadTotal;
    private long recordedUploadTotal;
    private long recordedDownloadTotal;
    private long lastLocalTrafficPersistAt;
    private volatile long lastNotificationAt;

    public static boolean isLive() { return live; }

    static InetAddress[] resolveProbeHost(String host) throws Exception {
        VpnCoreService service = activeInstance;
        if (service == null || !CoreState.read(service).isActive()) return InetAddress.getAllByName(host);
        Network physical = service.physicalNetwork();
        InetAddress[] answers = physical == null ? InetAddress.getAllByName(host) : physical.getAllByName(host);
        for (InetAddress answer : answers) if (answer instanceof java.net.Inet4Address) return answers;
        throw new UnknownHostException("物理网络未返回 IPv4 地址");
    }

    static Socket createProbeSocket() throws Exception {
        VpnCoreService service = activeInstance;
        if (service == null || !CoreState.read(service).isActive()) return new Socket();
        Network physical = service.physicalNetwork();
        Socket socket;
        try { socket = physical == null ? new Socket() : physical.getSocketFactory().createSocket(); }
        catch (Exception ignored) { socket = new Socket(); }
        if (!service.protect(socket) && physical == null) {
            try { socket.close(); } catch (Exception ignored) {}
            throw new IOException("无法让测速连接绕过当前 VPN");
        }
        return socket;
    }

    static TunnelHealth checkTunnelHealthNow() {
        VpnCoreService service = activeInstance;
        if (service == null || CoreState.read(service).state != CoreState.RUNNING) return TunnelHealth.failure("VPN 尚未连接");
        return service.probeTunnelOnce();
    }

    static TunnelHealth checkTunnelHealthNow(String target) {
        VpnCoreService service = activeInstance;
        if (service == null || CoreState.read(service).state != CoreState.RUNNING) return TunnelHealth.failure("VPN 尚未连接");
        String safe = normalizeHealthTarget(target);
        return safe.isEmpty() ? TunnelHealth.failure("测试网址无效") : service.probeTunnelOnce(new String[]{safe});
    }

    static TunnelHealth lastTunnelHealthNow() {
        VpnCoreService service = activeInstance;
        if (service == null || CoreState.read(service).state != CoreState.RUNNING) return TunnelHealth.failure("VPN 尚未连接");
        return service.lastTunnelHealth;
    }

    static boolean isSwitchInProgressNow() {
        VpnCoreService service = activeInstance;
        return service != null && service.switchInProgress.get();
    }

    static void start(Context context, String config, int nodeId, String nodeName, String routeLabel) {
        start(context, config, nodeId, nodeName, routeLabel, "");
    }

    static void start(Context context, String config, int nodeId, String nodeName, String routeLabel, String healthTarget) {
        Intent intent = new Intent(context, VpnCoreService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config)
                .putExtra(EXTRA_NODE_ID, nodeId)
                .putExtra(EXTRA_NODE_NAME, value(nodeName))
                .putExtra(EXTRA_ROUTE_LABEL, value(routeLabel))
                .putExtra(EXTRA_HEALTH_TARGET, normalizeHealthTarget(healthTarget));
        context.startForegroundService(intent);
    }

    static void reconfigure(Context context, String config, int nodeId, String nodeName, String routeLabel) {
        reconfigure(context, config, nodeId, nodeName, routeLabel, "");
    }

    static void reconfigure(Context context, String config, int nodeId, String nodeName, String routeLabel, String healthTarget) {
        Intent intent = new Intent(context, VpnCoreService.class)
                .setAction(ACTION_RECONFIGURE)
                .putExtra(EXTRA_CONFIG, config)
                .putExtra(EXTRA_NODE_ID, nodeId)
                .putExtra(EXTRA_NODE_NAME, value(nodeName))
                .putExtra(EXTRA_ROUTE_LABEL, value(routeLabel))
                .putExtra(EXTRA_HEALTH_TARGET, normalizeHealthTarget(healthTarget));
        context.startService(intent);
    }

    static void stop(Context context) {
        if (!live) {
            CoreState.Snapshot state = CoreState.read(context);
            CoreState.publishLifecycle(context, CoreState.STOPPED, state.nodeId, state.nodeName, "");
            return;
        }
        context.startService(new Intent(context, VpnCoreService.class).setAction(ACTION_STOP));
    }

    static void stopAndForget(Context context) {
        SecureVpnProfileStore.clear(context);
        stop(context);
    }

    @Override public void onCreate() {
        super.onCreate();
        live = true;
        activeInstance = this;
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        createNotificationChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            requestStop();
            return START_NOT_STICKY;
        }

        if (ACTION_RECONFIGURE.equals(action) && intent != null) {
            if (CoreState.read(this).state != CoreState.RUNNING || !tunStarted) return START_NOT_STICKY;
            String config = intent.getStringExtra(EXTRA_CONFIG);
            int nextNodeId = intent.getIntExtra(EXTRA_NODE_ID, 0);
            if (config == null || config.trim().isEmpty() || nextNodeId <= 0) return START_NOT_STICKY;
            if (!switchInProgress.compareAndSet(false, true)) return START_NOT_STICKY;
            String nextNodeName = value(intent.getStringExtra(EXTRA_NODE_NAME));
            String nextRouteLabel = value(intent.getStringExtra(EXTRA_ROUTE_LABEL));
            String healthTarget = normalizeHealthTarget(intent.getStringExtra(EXTRA_HEALTH_TARGET));
            CoreState.publishLifecycle(this, CoreState.SWITCHING, nextNodeId, nextNodeName, "");
            updateForegroundNotification("XVPN 正在切换", "正在应用 " + (nextNodeName.isEmpty() ? "当前节点" : nextNodeName), false);
            coreExecutor.execute(() -> reconfigureCore(config, nextNodeId, nextNodeName, nextRouteLabel, healthTarget));
            return START_STICKY;
        }

        if (CoreState.read(this).isActive() && tunStarted) return START_STICKY;
        boolean explicitStart = ACTION_START.equals(action) && intent != null;
        String config;
        String healthTarget;
        if (explicitStart) {
            config = intent.getStringExtra(EXTRA_CONFIG);
            healthTarget = normalizeHealthTarget(intent.getStringExtra(EXTRA_HEALTH_TARGET));
            nodeId = intent.getIntExtra(EXTRA_NODE_ID, 0);
            nodeName = value(intent.getStringExtra(EXTRA_NODE_NAME));
            routeLabel = value(intent.getStringExtra(EXTRA_ROUTE_LABEL));
            SecureVpnProfileStore.clear(this);
        } else {
            SecureVpnProfileStore.Profile profile = SecureVpnProfileStore.load(this);
            if (profile == null) {
                CoreState.publishLifecycle(this, CoreState.STOPPED, 0, "", "");
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            config = profile.yaml;
            healthTarget = normalizeHealthTarget(profile.healthTarget);
            nodeId = profile.nodeId;
            nodeName = value(profile.nodeName);
            routeLabel = value(profile.routeLabel);
        }
        if (routeLabel.isEmpty()) routeLabel = "智能分流";
        if (config == null || config.trim().isEmpty() || nodeId <= 0) {
            failStart("节点配置为空，请刷新节点后重试");
            return START_NOT_STICKY;
        }

        stopRequested.set(false);
        sessionId = UUID.randomUUID().toString();
        CoreState.publishLifecycle(this, CoreState.STARTING, nodeId, nodeName, "");
        startForeground(NOTIFICATION_ID, serviceNotification("XVPN 正在连接", displayNodeName() + " · " + routeLabel, true, false));
        coreExecutor.execute(() -> startCore(config, healthTarget));
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }

    @Override public void onRevoke() {
        SecureVpnProfileStore.clear(this);
        requestStop();
    }

    @Override public void onDestroy() {
        live = false;
        if (activeInstance == this) activeInstance = null;
        stopRequested.set(true);
        shutdownSchedulers(false);
        cleanupCoreObjects();
        CoreState.Snapshot state = CoreState.read(this);
        if (state.isActive()) CoreState.publishLifecycle(this, CoreState.STOPPED, state.nodeId, state.nodeName, "");
        coreExecutor.shutdownNow();
        reportIo.shutdown();
        super.onDestroy();
    }

    private void startCore(String config, String healthTarget) {
        try {
            enforcePanelUpdatePolicy();
            ensureMihomoLoaded();
            applyMihomoProfile(config, routeLabel);
            if (stopRequested.get()) return;
            establishAndStartTun();
            if (stopRequested.get()) { stopCoreInternal(); return; }

            TunnelHealth health = waitForInitialTunnelHealth(healthTarget);
            if (!health.healthy) throw new IllegalStateException("隧道已建立，但联网检测失败：" + health.error);

            lastTunnelHealth = health;
            activeConfig = config;
            activeHealthTarget = healthTarget;
            persistActiveProfile();
            CoreState.publishLifecycle(this, CoreState.RUNNING, nodeId, nodeName, "");
            updateForegroundNotification("XVPN 已连接", connectedNotificationText(0L, 0L), true);
            startMetricsAndReporting();
        } catch (Throwable error) {
            logCoreFailure("Mihomo start failed", error);
            cleanupCoreObjects();
            failStart(friendlyCoreError(error));
        }
    }

    /**
     * Always-on can start without opening MainActivity, so the service checks
     * the same Panel policy before loading native code. Network/source outages
     * are retried later and do not strand an otherwise valid Always-on tunnel.
     */
    private void enforcePanelUpdatePolicy() {
        try {
            SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
            String panel = ApiClient.normalizePanelBase(prefs.getString("base_url", ""));
            if (panel.isEmpty()) return;
            String query = "/app/update?version_name=" + BuildConfig.VERSION_NAME
                    + "&version_code=" + BuildConfig.VERSION_CODE;
            JSONObject result = ApiClient.request(panel, query, "GET", null, null);
            if (result.optBoolean("must_update", false)
                    || result.optBoolean("force_update", false)
                    || result.optBoolean("mustUpdate", false)
                    || result.optBoolean("forceUpdate", false)) {
                SecureVpnProfileStore.clear(this);
                CoreState.notifyVersionBlocked(this);
                throw new MinimumVersionException();
            }
        } catch (MinimumVersionException blocked) {
            throw blocked;
        } catch (Exception unavailable) {
            Log.w(TAG, "Panel update policy check deferred");
        }
    }

    private void reconfigureCore(String config, int nextNodeId, String nextNodeName, String nextRouteLabel, String healthTarget) {
        int previousNodeId = nodeId;
        String previousNodeName = nodeName;
        String previousRouteLabel = routeLabel;
        String previousConfig = activeConfig;
        String previousHealthTarget = activeHealthTarget;
        boolean schedulersStopped = false;
        try {
            updateMetricsSnapshot();
            ReportSnapshot previous = currentReportSnapshot();
            shutdownSchedulers(false);
            schedulersStopped = true;
            if (previous != null) reportIo.execute(() -> reportTrafficSafe(previous));

            String effectiveLabel = value(nextRouteLabel).isEmpty() ? routeLabel : value(nextRouteLabel);
            applyMihomoProfile(config, effectiveLabel);
            nodeId = nextNodeId;
            nodeName = value(nextNodeName);
            routeLabel = effectiveLabel;
            sessionId = UUID.randomUUID().toString();

            TunnelHealth health = healthTarget.isEmpty() ? waitForTunnelHealth() : waitForTunnelHealth(healthTarget);
            if (!health.healthy) throw new IllegalStateException("新配置联网检测失败：" + health.error);

            lastTunnelHealth = health;
            activeConfig = config;
            activeHealthTarget = healthTarget;
            persistActiveProfile();
            CoreState.publishLifecycle(this, CoreState.RUNNING, nodeId, nodeName, "");
            updateForegroundNotification("XVPN 已连接", connectedNotificationText(0L, 0L), true);
            startMetricsAndReporting();
        } catch (Throwable error) {
            logCoreFailure("Mihomo reconfigure failed", error);
            String message = friendlyCoreError(error);
            if (!stopRequested.get() && !previousConfig.isEmpty()) {
                try {
                    applyMihomoProfile(previousConfig, previousRouteLabel);
                    TunnelHealth restored = previousHealthTarget.isEmpty() ? waitForTunnelHealth() : waitForTunnelHealth(previousHealthTarget);
                    if (!restored.healthy) throw new IllegalStateException("原连接恢复后仍无法联网：" + restored.error);
                    lastTunnelHealth = restored;
                    nodeId = previousNodeId;
                    nodeName = previousNodeName;
                    routeLabel = previousRouteLabel;
                    activeConfig = previousConfig;
                    activeHealthTarget = previousHealthTarget;
                    sessionId = UUID.randomUUID().toString();
                    persistActiveProfile();
                    CoreState.publishLifecycle(this, CoreState.RUNNING, nodeId, nodeName, "");
                    updateForegroundNotification("XVPN 已连接", connectedNotificationText(0L, 0L), true);
                    if (schedulersStopped) startMetricsAndReporting();
                    CoreState.notifySwitchFailed(this, "切换失败，已保留原连接 · " + message, previousNodeId, previousRouteLabel);
                    return;
                } catch (Throwable rollbackError) {
                    logCoreFailure("Mihomo rollback failed", rollbackError);
                }
            }
            cleanupCoreObjects();
            failStart(message);
        } finally {
            switchInProgress.set(false);
        }
    }

    private void ensureMihomoLoaded() {
        synchronized (CORE_LOCK) {
            if (mihomoReady) return;
            Clash.INSTANCE.load(getApplicationInfo().nativeLibraryDir);
            Clash.INSTANCE.assertReady();
            int abi = Clash.INSTANCE.bridgeABI();
            if (abi != Clash.EXPECTED_BRIDGE_ABI) throw new IllegalStateException("Mihomo bridge ABI 不匹配：" + abi);
            mihomoReady = true;
            Log.i(TAG, "Mihomo bridge ready, ABI=" + abi);
        }
    }

    private void applyMihomoProfile(String profile, String ignoredLabel) throws Exception {
        if (profile == null || profile.trim().isEmpty()) {
            throw new IllegalArgumentException("Mihomo 配置为空");
        }
        File home = new File(getFilesDir(), "mihomo");
        if (!home.exists() && !home.mkdirs()) throw new IOException("无法创建 Mihomo 配置目录");
        File config = new File(home, "config.yaml");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(config, false), StandardCharsets.UTF_8)) {
            writer.write(profile);
            writer.flush();
        }

        JSONObject init = new JSONObject().put("home-dir", home.getAbsolutePath()).put("version", Build.VERSION.SDK_INT);
        JSONObject setup = new JSONObject().put("selected-map", new JSONObject());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>(null);
        Clash.INSTANCE.quickSetup(init.toString(), setup.toString(), new InvokeInterface() {
            @Override public void onResult(String value) {
                result.set(value == null ? "" : value);
                latch.countDown();
            }
        });
        if (!latch.await(12L, TimeUnit.SECONDS)) throw new IOException("Mihomo 配置加载超时");
        String message = value(result.get());
        if (!message.isEmpty()) throw new IllegalArgumentException("Mihomo 配置错误：" + message);
    }

    private void establishAndStartTun() throws Exception {
        Builder builder = new Builder()
                .setSession("XVPN")
                .setMtu(MihomoProfileBuilder.TUN_MTU)
                .addAddress("172.19.0.1", 30)
                .addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("172.19.0.2")
                .addDnsServer("fdfe:dcba:9876::2")
                .setBlocking(true);
        Network physical = physicalNetwork();
        if (physical != null) builder.setUnderlyingNetworks(new Network[]{physical});
        tunDescriptor = builder.establish();
        if (tunDescriptor == null) throw new IOException("系统未能建立 VPN TUN");
        if (physical != null) setUnderlyingNetworks(new Network[]{physical});

        TunInterface callback = new TunInterface() {
            @Override public void protect(int fd) { VpnCoreService.this.protect(fd); }
            @Override public String resolverProcess(int protocol, String source, String target, int uid) { return ""; }
        };
        Clash.INSTANCE.startTUN(
                tunDescriptor.getFd(), callback, "xvpn0", "mixed",
                "172.19.0.1/30,fdfe:dcba:9876::1/126",
                "172.19.0.2,fdfe:dcba:9876::2",
                MihomoProfileBuilder.TUN_MTU);
        tunStarted = true;
    }

    private void persistActiveProfile() {
        try { SecureVpnProfileStore.save(this, activeConfig, nodeId, nodeName, routeLabel, activeHealthTarget); }
        catch (Exception error) {
            Log.e(TAG, "Unable to persist encrypted Always-on profile", error);
            SecureVpnProfileStore.clear(this);
        }
    }

    private void requestStop() {
        if (!stopRequested.compareAndSet(false, true)) return;
        CoreState.Snapshot state = CoreState.read(this);
        CoreState.publishLifecycle(this, CoreState.STOPPING,
                state.nodeId > 0 ? state.nodeId : nodeId,
                state.nodeName.isEmpty() ? nodeName : state.nodeName, "");
        updateForegroundNotification("XVPN 正在断开", displayNodeName() + " · 正在保存连接数据", false);
        coreExecutor.execute(this::stopCoreInternal);
    }

    private void stopCoreInternal() {
        updateMetricsSnapshot();
        shutdownSchedulers(true);
        cleanupCoreObjects();
        CoreState.publishLifecycle(this, CoreState.STOPPED, nodeId, nodeName, "");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void cleanupCoreObjects() {
        if (tunStarted && mihomoReady) {
            try { Clash.INSTANCE.stopTun(); } catch (Throwable ignored) {}
        }
        tunStarted = false;
        ParcelFileDescriptor descriptor = tunDescriptor;
        tunDescriptor = null;
        if (descriptor != null) try { descriptor.close(); } catch (Exception ignored) {}
    }

    private void failStart(String message) {
        CoreState.publishLifecycle(this, CoreState.ERROR, nodeId, nodeName, value(message));
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private TunnelHealth waitForInitialTunnelHealth(String healthTarget) {
        return healthTarget.isEmpty() ? waitForTunnelHealth(defaultHealthTargets(), 3) : waitForTunnelHealth(new String[]{healthTarget}, 3);
    }

    private TunnelHealth waitForTunnelHealth() { return waitForTunnelHealth(defaultHealthTargets(), 2); }
    private TunnelHealth waitForTunnelHealth(String target) { return waitForTunnelHealth(new String[]{target}, 2); }

    private TunnelHealth waitForTunnelHealth(String[] targets, int attempts) {
        TunnelHealth last = TunnelHealth.failure("网络暂无响应");
        for (int attempt = 0; attempt < attempts && !stopRequested.get(); attempt++) {
            last = probeTunnelOnce(targets);
            if (last.healthy) return last;
            if (attempt + 1 < attempts) {
                try { Thread.sleep(600L); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); return TunnelHealth.failure("联网检测已取消"); }
            }
        }
        return last;
    }

    private TunnelHealth probeTunnelOnce() { return probeTunnelOnce(defaultHealthTargets()); }

    private String[] defaultHealthTargets() {
        return new String[]{"http://www.apple.com/library/test/success.html", "https://github.com/favicon.ico"};
    }

    private TunnelHealth probeTunnelOnce(String[] targets) {
        String lastError = "无法访问检测网站";
        for (String target : targets) {
            HttpURLConnection connection = null;
            long started = System.nanoTime();
            try {
                connection = (HttpURLConnection) new URL(target).openConnection();
                connection.setConnectTimeout(3500);
                connection.setReadTimeout(3500);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Connection", "close");
                connection.setRequestProperty("User-Agent", "XVPN-Android/" + BuildConfig.VERSION_NAME);
                int status = connection.getResponseCode();
                if (status >= 200 && status < 400) {
                    long latency = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
                    return TunnelHealth.success(new URL(target).getHost(), latency);
                }
                lastError = new URL(target).getHost() + " 返回 HTTP " + status;
            } catch (Exception error) {
                String name = error.getClass().getSimpleName();
                if (name.contains("UnknownHost")) lastError = "DNS 解析失败";
                else if (name.contains("SocketTimeout")) lastError = "代理出口响应超时";
                else if (name.contains("SSL")) lastError = "代理出口 TLS 握手失败";
                else if (name.contains("Connect")) lastError = "代理出口连接失败";
                else lastError = value(error.getMessage()).isEmpty() ? "联网检测失败" : value(error.getMessage());
            } finally { if (connection != null) connection.disconnect(); }
        }
        return TunnelHealth.failure(lastError);
    }

    private Network physicalNetwork() {
        if (connectivity == null) return null;
        Network fallback = null;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            boolean common = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            if (common && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return network;
            if (fallback == null) fallback = network;
        }
        return fallback;
    }

    // ----- Mihomo-native metrics + Panel cumulative reporting -----

    private long[] coreTraffic(boolean totals) {
        try {
            String raw = totals ? Clash.INSTANCE.getTotalTraffic() : Clash.INSTANCE.getTraffic();
            JSONObject json = new JSONObject(raw);
            return new long[]{Math.max(0L, json.optLong("up", 0L)), Math.max(0L, json.optLong("down", 0L))};
        } catch (Throwable ignored) { return new long[]{0L, 0L}; }
    }

    private void startMetricsAndReporting() {
        long[] baseline = coreTraffic(true);
        coreBaselineUp = baseline[0];
        coreBaselineDown = baseline[1];
        uploadTotal = downloadTotal = 0L;
        recordedUploadTotal = recordedDownloadTotal = 0L;
        lastLocalTrafficPersistAt = SystemClock.elapsedRealtime();
        lastNotificationAt = 0L;

        metricsExecutor = Executors.newSingleThreadScheduledExecutor();
        metricsExecutor.scheduleWithFixedDelay(this::updateMetricsSnapshot, 1L, 1L, TimeUnit.SECONDS);

        SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("traffic_reporting", false)) return;
        long interval = Math.max(5L, Math.min(300L, prefs.getLong("traffic_report_interval_seconds", 5L)));
        reportExecutor = Executors.newSingleThreadScheduledExecutor();
        reportExecutor.scheduleWithFixedDelay(() -> {
            ReportSnapshot report = currentReportSnapshot();
            if (report != null) reportIo.execute(() -> reportTrafficSafe(report));
        }, 0L, interval, TimeUnit.SECONDS);
    }

    private void updateMetricsSnapshot() {
        int state = CoreState.read(this).state;
        if (state != CoreState.RUNNING && state != CoreState.SWITCHING && state != CoreState.STOPPING && !stopRequested.get()) return;
        long[] rates = coreTraffic(false);
        long[] totals = coreTraffic(true);
        uploadTotal = Math.max(0L, totals[0] - coreBaselineUp);
        downloadTotal = Math.max(0L, totals[1] - coreBaselineDown);
        CoreState.publishMetrics(this, uploadTotal, downloadTotal, rates[0], rates[1]);
        persistLocalTrafficDelta(false);
        long now = SystemClock.elapsedRealtime();
        if (state == CoreState.RUNNING && now - lastNotificationAt >= 2000L) {
            lastNotificationAt = now;
            updateForegroundNotification("XVPN 已连接", connectedNotificationText(rates[0], rates[1]), true);
        }
    }

    private ReportSnapshot currentReportSnapshot() {
        SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("traffic_reporting", false) || nodeId <= 0 || sessionId.isEmpty()) return null;
        return new ReportSnapshot(nodeId, sessionId, uploadTotal, downloadTotal);
    }

    private void reportTrafficSafe(ReportSnapshot report) {
        if (report == null) return;
        try {
            SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
            String panel = ApiClient.normalizePanelBase(prefs.getString("base_url", ""));
            String auth = SecureTokenStore.load(prefs);
            if (panel.isEmpty() || auth == null || auth.isEmpty()) return;
            JSONObject body = new JSONObject()
                    .put("device_id", persistentDeviceId())
                    .put("session_id", report.sessionId)
                    .put("node_id", report.nodeId)
                    .put("upload_total_bytes", Math.max(0L, report.uploadTotal))
                    .put("download_total_bytes", Math.max(0L, report.downloadTotal))
                    .put("app_version", BuildConfig.VERSION_NAME);
            ApiClient.request(panel, "/traffic/report", "POST", auth, body);
            if (versionBlockNotified.getAndSet(false)) updateForegroundNotification("XVPN 已连接", connectedNotificationText(0L, 0L), true);
        } catch (ApiClient.ApiException error) {
            if (error.isVersionBlocked()) {
                if (versionBlockNotified.compareAndSet(false, true)) {
                    CoreState.notifyVersionBlocked(this);
                    updateForegroundNotification("XVPN 需要更新", displayNodeName() + " · 请返回 App 完成更新", true);
                }
            } else if (error.isAuthFailure()) {
                SecureVpnProfileStore.clear(this);
                CoreState.notifyAuthInvalid(this, error.code);
                requestStop();
            } else if ("INVALID_NODE_ID".equals(error.code)) {
                if (report.sessionId.equals(sessionId)) {
                    SecureVpnProfileStore.clear(this);
                    CoreState.notifyNodeInvalid(this);
                    requestStop();
                }
            } else if (!"TRAFFIC_USER_REQUIRED".equals(error.code)) {
                Log.w(TAG, "Traffic report rejected: " + error.code);
            }
        } catch (Exception error) { Log.w(TAG, "Traffic report deferred", error); }
    }

    private String persistentDeviceId() {
        SharedPreferences identity = getSharedPreferences("xvpn_device_identity_v1", MODE_PRIVATE);
        String id = identity.getString("device_id", "");
        if (id == null || id.length() < 8) {
            id = UUID.randomUUID().toString();
            identity.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private void shutdownSchedulers(boolean finalReport) {
        persistLocalTrafficDelta(true);
        ReportSnapshot finalSnapshot = finalReport ? currentReportSnapshot() : null;
        ScheduledExecutorService metrics = metricsExecutor;
        metricsExecutor = null;
        if (metrics != null) metrics.shutdownNow();
        ScheduledExecutorService reports = reportExecutor;
        reportExecutor = null;
        if (reports != null) reports.shutdownNow();
        if (finalSnapshot != null) reportIo.execute(() -> reportTrafficSafe(finalSnapshot));
    }

    private synchronized void persistLocalTrafficDelta(boolean force) {
        long nextUp = Math.max(0L, uploadTotal);
        long nextDown = Math.max(0L, downloadTotal);
        long deltaUp = Math.max(0L, nextUp - recordedUploadTotal);
        long deltaDown = Math.max(0L, nextDown - recordedDownloadTotal);
        if (deltaUp == 0L && deltaDown == 0L) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastLocalTrafficPersistAt < 5000L && deltaUp + deltaDown < 65536L) return;

        SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        String day = java.time.LocalDate.now().toString();
        String month = day.length() >= 7 ? day.substring(0, 7) : day;
        boolean sameDay = day.equals(prefs.getString("local_traffic_day_key", ""));
        boolean sameMonth = month.equals(prefs.getString("local_traffic_month_key", ""));
        long todayUp = sameDay ? prefs.getLong("local_traffic_today_up", 0L) : 0L;
        long todayDown = sameDay ? prefs.getLong("local_traffic_today_down", 0L) : 0L;
        long monthUp = sameMonth ? prefs.getLong("local_traffic_month_up", 0L) : 0L;
        long monthDown = sameMonth ? prefs.getLong("local_traffic_month_down", 0L) : 0L;
        prefs.edit()
                .putString("local_traffic_day_key", day)
                .putString("local_traffic_month_key", month)
                .putLong("local_traffic_today_up", todayUp + deltaUp)
                .putLong("local_traffic_today_down", todayDown + deltaDown)
                .putLong("local_traffic_month_up", monthUp + deltaUp)
                .putLong("local_traffic_month_down", monthDown + deltaDown)
                .putLong("local_traffic_total_up", prefs.getLong("local_traffic_total_up", 0L) + deltaUp)
                .putLong("local_traffic_total_down", prefs.getLong("local_traffic_total_down", 0L) + deltaDown)
                .apply();
        recordedUploadTotal = nextUp;
        recordedDownloadTotal = nextDown;
        lastLocalTrafficPersistAt = now;
    }

    // ----- Foreground notification -----

    private void createNotificationChannels() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL, "XVPN 连接状态", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示当前节点、分流模式、实时速率与安全断开操作");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
    }

    private android.app.Notification serviceNotification(String title, String content, boolean ongoing, boolean chronometer) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, VpnCoreService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        CoreState.Snapshot snapshot = CoreState.read(this);
        String connectionLine = displayNodeName() + " · " + routeLabel;
        android.app.Notification.Style style = chronometer
                ? new android.app.Notification.InboxStyle().setBigContentTitle(title)
                    .addLine(connectionLine)
                    .addLine("↑ " + compactRate(snapshot.uploadRate) + "    ↓ " + compactRate(snapshot.downloadRate))
                    .addLine("Mihomo · DNS 与代理出口已验证").setSummaryText("PRIVATE NETWORK")
                : new android.app.Notification.BigTextStyle().setBigContentTitle(title).bigText(content).setSummaryText("PRIVATE NETWORK");
        android.app.Notification.Builder builder = new android.app.Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_vpn_status)
                .setContentTitle(title)
                .setContentText(chronometer ? connectionLine : content)
                .setSubText("PRIVATE NETWORK")
                .setStyle(style)
                .setContentIntent(open)
                .setOngoing(ongoing)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setColor(chronometer ? 0xFF22B78B : 0xFF6487FF)
                .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
                .setCategory(android.app.Notification.CATEGORY_SERVICE)
                .addAction(new android.app.Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_vpn_status), "安全断开", stop).build());
        if (chronometer) {
            long startedAt = snapshot.startedAt;
            builder.setWhen(startedAt > 0L ? startedAt : System.currentTimeMillis()).setUsesChronometer(true).setShowWhen(true);
        } else builder.setShowWhen(false).setUsesChronometer(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setForegroundServiceBehavior(android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private void updateForegroundNotification(String title, String text, boolean chronometer) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, serviceNotification(title, text, true, chronometer));
    }

    private String displayNodeName() { return nodeName.isEmpty() ? "XVPN 节点" : nodeName; }

    private String connectedNotificationText(long uploadRate, long downloadRate) {
        return displayNodeName() + " · " + routeLabel + "   ↑ " + compactRate(uploadRate) + "   ↓ " + compactRate(downloadRate);
    }

    private String compactRate(long bytesPerSecond) {
        double number = Math.max(0L, bytesPerSecond);
        String[] units = {"B/s", "KB/s", "MB/s", "GB/s"};
        int unit = 0;
        while (number >= 1024d && unit < units.length - 1) { number /= 1024d; unit++; }
        if (unit == 0) return ((long) number) + " " + units[unit];
        return String.format(Locale.US, number >= 100d ? "%.0f %s" : "%.1f %s", number, units[unit]);
    }

    private String friendlyCoreError(Throwable error) {
        if (error instanceof MinimumVersionException) return "当前版本已停用，请更新 XVPN 后继续使用";
        String message = error == null ? "" : value(error.getMessage());
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("permission") || lower.contains("prepared") || lower.contains("revoked")) return "VPN 授权已失效，请重新连接";
        if (lower.contains("reality") && lower.contains("public")) return "节点 REALITY 公钥配置无效";
        if (lower.contains("unsupported") || lower.contains("not support")) return "当前节点协议暂不受 Mihomo 测试版支持";
        if (message.isEmpty()) return "Mihomo 内核启动失败，请检查节点配置";
        message = message.replaceAll("(?i)(vless|trojan|vmess|ss|shadowsocks|hysteria2|hy2|tuic|anytls)://[^\\s]+", "$1://••••")
                .replaceAll("(?i)(\"(?:password|uuid|private-key|token)\"\\s*:\\s*\")[^\"]*(\")", "$1••••$2")
                .replaceAll("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "••••");
        return message.length() > 220 ? message.substring(0, 220) + "…" : message;
    }

    private void logCoreFailure(String stage, Throwable error) {
        if (BuildConfig.DEBUG) Log.e(TAG, stage, error);
        else Log.e(TAG, stage + ": " + friendlyCoreError(error));
    }

    private static String normalizeHealthTarget(String target) {
        String candidate = value(target);
        if (candidate.isEmpty()) return "";
        if (!candidate.contains("://")) candidate = "https://" + candidate;
        try {
            URL url = new URL(candidate);
            String scheme = url.getProtocol();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return "";
            if (value(url.getHost()).isEmpty() || url.getUserInfo() != null) return "";
            int port = url.getPort();
            if (port == 0 || port > 65535) return "";
            return url.toExternalForm();
        } catch (Exception ignored) { return ""; }
    }

    private static String value(String text) { return text == null ? "" : text.trim(); }

    private static final class MinimumVersionException extends RuntimeException {
        MinimumVersionException() { super("当前版本已停用，请更新 XVPN 后继续使用"); }
    }

    static final class TunnelHealth {
        final boolean healthy;
        final String endpoint;
        final long latencyMs;
        final String error;

        private TunnelHealth(boolean healthy, String endpoint, long latencyMs, String error) {
            this.healthy = healthy;
            this.endpoint = value(endpoint);
            this.latencyMs = Math.max(0L, latencyMs);
            this.error = value(error);
        }

        static TunnelHealth success(String endpoint, long latencyMs) { return new TunnelHealth(true, endpoint, latencyMs, ""); }
        static TunnelHealth failure(String error) { return new TunnelHealth(false, "", 0L, error); }
    }

    private static final class ReportSnapshot {
        final int nodeId;
        final String sessionId;
        final long uploadTotal;
        final long downloadTotal;

        ReportSnapshot(int nodeId, String sessionId, long uploadTotal, long downloadTotal) {
            this.nodeId = nodeId;
            this.sessionId = sessionId;
            this.uploadTotal = uploadTotal;
            this.downloadTotal = downloadTotal;
        }
    }
}
