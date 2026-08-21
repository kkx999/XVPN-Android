package com.xvpn.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted last-known-good profile used only for Android Always-on recovery. */
final class SecureVpnProfileStore {
    private static final String STORE = "xvpn_always_on_profile_v1";
    private static final String KEY_ALIAS = "xvpn_always_on_profile_key_v1";
    private static final String VALUE = "profile_enc";
    private static final String IV = "profile_iv";

    private SecureVpnProfileStore() {}

    static void save(Context context, String config, int nodeId, String nodeName,
                     String routeLabel, String healthTarget) throws Exception {
        JSONObject profile = new JSONObject()
                .put("config", config == null ? "" : config)
                .put("node_id", nodeId)
                .put("node_name", nodeName == null ? "" : nodeName)
                .put("route_label", routeLabel == null ? "" : routeLabel)
                .put("health_target", healthTarget == null ? "" : healthTarget);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(profile.toString().getBytes(StandardCharsets.UTF_8));
        boolean stored = prefs(context).edit()
                .putString(VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .commit();
        if (!stored) throw new IllegalStateException("无法保存 Always-on 配置");
    }

    static Profile load(Context context) {
        SharedPreferences prefs = prefs(context);
        String encrypted = prefs.getString(VALUE, "");
        String iv = prefs.getString(IV, "");
        if (encrypted == null || encrypted.isEmpty() || iv == null || iv.isEmpty()) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            JSONObject json = new JSONObject(new String(
                    cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8));
            Profile profile = new Profile(
                    json.optString("config", ""), json.optInt("node_id", 0),
                    json.optString("node_name", ""), json.optString("route_label", ""),
                    json.optString("health_target", ""));
            if (profile.config.trim().isEmpty() || profile.nodeId <= 0) throw new IllegalStateException("Invalid profile");
            return profile;
        } catch (Exception error) {
            clear(context);
            return null;
        }
    }

    static void clear(Context context) {
        prefs(context).edit().remove(VALUE).remove(IV).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    static final class Profile {
        final String config;
        final int nodeId;
        final String nodeName;
        final String routeLabel;
        final String healthTarget;

        Profile(String config, int nodeId, String nodeName, String routeLabel, String healthTarget) {
            this.config = config;
            this.nodeId = nodeId;
            this.nodeName = nodeName == null ? "" : nodeName;
            this.routeLabel = routeLabel == null ? "" : routeLabel;
            this.healthTarget = healthTarget == null ? "" : healthTarget;
        }
    }
}
