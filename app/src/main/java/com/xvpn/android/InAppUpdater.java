package com.xvpn.android;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Downloads, verifies and hands a same-signature APK to Android's package installer. */
final class InAppUpdater {
    private static final long MAX_APK_BYTES = 250L * 1024L * 1024L;
    private static final String FINAL_NAME = "xvpn-update.apk";
    private static final String PART_NAME = "xvpn-update.apk.part";

    interface Progress {
        void onProgress(long downloaded, long total, String stage);
    }

    private InAppUpdater() {}

    static File downloadAndVerify(Activity activity, AppUpdateChecker.Result update,
                                  AtomicBoolean cancelled, Progress progress) throws Exception {
        if (update == null || !update.supportsVerifiedInAppInstall()) {
            throw new SecurityException("更新信息缺少安全校验值");
        }
        File directory = new File(activity.getCacheDir(), "updates");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("无法创建更新缓存");
        File target = new File(directory, FINAL_NAME);
        File part = new File(directory, PART_NAME);
        deleteQuietly(part);
        deleteQuietly(target);

        URL current = new URL(update.apkUrl);
        long downloaded = 0L;
        for (int redirects = 0; redirects <= 5; redirects++) {
            requireHttps(current);
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "XVPN-Android/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive,application/octet-stream");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status <= 308 && status != 304) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty() || redirects == 5) {
                        throw new IllegalStateException("更新下载重定向无效");
                    }
                    current = new URL(current, location);
                    continue;
                }
                if (status < 200 || status >= 300) throw new IllegalStateException("更新下载失败（HTTP " + status + "）");
                long declared = connection.getContentLengthLong();
                long total = update.apkSize > 0L ? update.apkSize : declared;
                if (declared > MAX_APK_BYTES || total > MAX_APK_BYTES) throw new SecurityException("更新包大小异常");
                progress.onProgress(0L, total, "正在下载");
                try (InputStream in = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (cancelled.get()) throw new InterruptedException("下载已取消");
                        downloaded += count;
                        if (downloaded > MAX_APK_BYTES) throw new SecurityException("更新包大小异常");
                        out.write(buffer, 0, count);
                        progress.onProgress(downloaded, total, "正在下载");
                    }
                    out.getFD().sync();
                }
                if (update.apkSize > 0L && downloaded != update.apkSize) throw new SecurityException("更新包大小校验失败");
                progress.onProgress(downloaded, total, "正在校验安全签名");
                String actual = sha256(part);
                if (!actual.equalsIgnoreCase(update.sha256)) throw new SecurityException("更新包 SHA-256 校验失败");
                verifyPackage(activity, part, update.versionCode);
                if (!part.renameTo(target)) throw new IllegalStateException("无法完成更新文件写入");
                progress.onProgress(downloaded, total, "校验完成");
                return target;
            } finally {
                connection.disconnect();
            }
        }
        throw new IllegalStateException("更新下载重定向过多");
    }

    static boolean install(Activity activity, int permissionRequestCode) throws Exception {
        File apk = new File(new File(activity.getCacheDir(), "updates"), FINAL_NAME);
        if (!apk.isFile()) throw new IllegalStateException("已验证的更新包不存在，请重新下载");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivityForResult(settings, permissionRequestCode);
            return false;
        }
        Uri uri = UpdateFileProvider.uri(activity);
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT, false);
        activity.startActivity(install);
        return true;
    }

    static void cleanStale(Activity activity) {
        File directory = new File(activity.getCacheDir(), "updates");
        deleteQuietly(new File(directory, PART_NAME));
        File complete = new File(directory, FINAL_NAME);
        if (complete.isFile() && System.currentTimeMillis() - complete.lastModified() > 24L * 60L * 60L * 1000L) {
            deleteQuietly(complete);
        }
    }

    private static void verifyPackage(Activity activity, File apk, int expectedCode) throws Exception {
        PackageManager manager = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null || !activity.getPackageName().equals(archive.packageName)) {
            throw new SecurityException("更新包应用身份不匹配");
        }
        long archiveCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? archive.getLongVersionCode() : archive.versionCode;
        if (archiveCode != expectedCode || archiveCode <= BuildConfig.VERSION_CODE) {
            throw new SecurityException("更新包版本号不匹配");
        }
        PackageInfo installed = manager.getPackageInfo(activity.getPackageName(), flags);
        Set<String> installedSigners = signerDigests(installed);
        Set<String> archiveSigners = signerDigests(archive);
        if (installedSigners.isEmpty() || !installedSigners.equals(archiveSigners)) {
            throw new SecurityException("更新包签名与当前 XVPN 不一致");
        }
    }

    private static Set<String> signerDigests(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (info.signingInfo == null) return new HashSet<>();
            signatures = info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        Set<String> result = new HashSet<>();
        if (signatures != null) for (Signature signature : signatures) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            result.add(hex(digest.digest(signature.toByteArray())));
        }
        return result;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(String.format(Locale.US, "%02x", item & 0xff));
        return out.toString();
    }

    private static void requireHttps(URL url) {
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new SecurityException("更新下载仅允许 HTTPS");
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) file.deleteOnExit();
    }
}
