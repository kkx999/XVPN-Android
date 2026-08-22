package com.xvpn.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Installs XVPN's pinned Mihomo MRS rules before activities/services start.
 *
 * This provider intentionally has no data API. Android initializes providers
 * when the app process starts, including an Always-on VpnService process start.
 */
public final class MihomoRuleProvider extends ContentProvider {
    private static final String TAG = "XVPN-MihomoRules";

    @Override public boolean onCreate() {
        Context context = getContext();
        if (context == null) return true;
        try {
            File home = new File(context.getFilesDir(), "mihomo");
            File rules = new File(home, "rules");
            if (!rules.exists() && !rules.mkdirs()) {
                throw new IllegalStateException("无法创建 Mihomo 规则目录");
            }
            install(context,
                    "mihomo-rules/geosite-cn.mrs",
                    new File(rules, "geosite-cn.mrs"),
                    BuildConfig.MIHOMO_GEOSITE_CN_SHA256);
            install(context,
                    "mihomo-rules/geoip-cn.mrs",
                    new File(rules, "geoip-cn.mrs"),
                    BuildConfig.MIHOMO_GEOIP_CN_SHA256);
        } catch (Throwable error) {
            // Do not silently switch routing modes. If these files are absent,
            // Mihomo's file rule-provider config will fail explicitly on load.
            Log.e(TAG, "Unable to prepare bundled Mihomo rules", error);
        }
        return true;
    }

    private static void install(Context context, String assetPath, File target, String expectedSha256) throws Exception {
        String expected = expectedSha256 == null ? "" : expectedSha256.trim().toLowerCase(Locale.ROOT);
        if (!expected.matches("[0-9a-f]{64}")) throw new IllegalStateException("Mihomo 规则 SHA256 配置无效");
        if (target.isFile() && expected.equals(sha256(target))) return;

        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        if (temp.exists() && !temp.delete()) throw new IllegalStateException("无法清理 Mihomo 规则临时文件");
        try (InputStream input = context.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(temp, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 0) output.write(buffer, 0, read);
            }
            output.flush();
            output.getFD().sync();
        }

        String actual = sha256(temp);
        if (!expected.equals(actual)) {
            temp.delete();
            throw new IllegalStateException("Mihomo 内置规则校验失败：" + assetPath);
        }

        if (target.exists() && !target.delete()) {
            temp.delete();
            throw new IllegalStateException("无法替换 Mihomo 规则：" + target.getName());
        }
        if (!temp.renameTo(target)) {
            try (InputStream input = new FileInputStream(temp);
                 FileOutputStream output = new FileOutputStream(target, false)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read > 0) output.write(buffer, 0, read);
                }
                output.flush();
                output.getFD().sync();
            }
            if (!temp.delete()) Log.w(TAG, "Unable to delete rule temp file: " + temp);
        }
        if (!expected.equals(sha256(target))) {
            target.delete();
            throw new IllegalStateException("Mihomo 规则落盘后二次校验失败：" + target.getName());
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest.digest()) result.append(String.format(Locale.US, "%02x", b & 0xff));
        return result.toString();
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
