package com.pptflashlight;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import java.io.IOException;

/**
 * Writes module settings to {@link Settings.Global}.
 *
 * <p>A normal app cannot write Global settings without {@code WRITE_SECURE_SETTINGS}, so two
 * fallbacks are tried: the permission itself (grantable once over adb) and {@code su -c settings
 * put global}. Values are numeric only, which keeps the shell command free of quoting issues.
 */
final class ConfigWriter {

    private ConfigWriter() {
    }

    /** @return the mechanism that worked ("direct", "su"), or null if neither did. */
    static String putInt(Context context, String key, int value) {
        String text = Integer.toString(value);
        ContentResolver resolver = context.getContentResolver();

        try {
            Settings.Global.putString(resolver, key, text);
            if (text.equals(Settings.Global.getString(resolver, key))) {
                return "direct";
            }
        } catch (SecurityException e) {
            L.i("direct Settings.Global write denied for " + key + ", trying su");
        } catch (Throwable t) {
            L.w("direct Settings.Global write failed for " + key + ": " + t);
        }

        if (putViaSu(key, text)) {
            return "su";
        }
        return null;
    }

    private static boolean putViaSu(String key, String value) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[] {
                    "su", "-c", "settings put global " + key + " " + value});
            int exit = process.waitFor();
            return exit == 0;
        } catch (IOException e) {
            L.w("su is not usable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return false;
    }
}
