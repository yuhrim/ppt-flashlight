package com.pptflashlight;

import android.content.ContentResolver;
import android.provider.Settings;

/**
 * Module configuration, stored in {@link Settings.Global} so that the same values are visible to
 * the system_server hook, to the app UI and to the user over adb:
 *
 * <pre>
 * adb shell settings put global ppt_flashlight_keycode 231
 * adb shell settings put global ppt_flashlight_hold_ms 500
 * </pre>
 *
 * <p>Inside system_server the values are read by a background thread every couple of seconds and
 * published as an immutable snapshot. The input dispatch thread only ever reads that snapshot: it
 * must not block on a ContentResolver call while the input lock is held.
 */
public final class ModuleConfig {

    public static final String KEY_ENABLED = "ppt_flashlight_enabled";
    public static final String KEY_KEYCODE = "ppt_flashlight_keycode";
    public static final String KEY_SCANCODE = "ppt_flashlight_scancode";
    public static final String KEY_HOLD_MS = "ppt_flashlight_hold_ms";
    public static final String KEY_SUPPRESS = "ppt_flashlight_suppress";
    public static final String KEY_SYSFS_TORCH = "ppt_flashlight_sysfs_torch";
    public static final String KEY_SYSFS_PATH = "ppt_flashlight_sysfs_path";

    public static final int DEFAULT_KEYCODE = 231;    // linux KEY_SEND, as reported by getevent
    public static final int DEFAULT_SCANCODE = 231;
    public static final int DEFAULT_HOLD_MS = 500;
    public static final String DEFAULT_SYSFS_PATH =
            "/sys/devices/virtual/msm_camera_flash/camera_flash_set/camera_flash";

    private static final long REFRESH_INTERVAL_MS = 2000L;

    private static final Snapshot DEFAULTS = new Snapshot(true, DEFAULT_KEYCODE, DEFAULT_SCANCODE,
            DEFAULT_HOLD_MS, false, false, DEFAULT_SYSFS_PATH);

    private static volatile Snapshot current = DEFAULTS;
    private static boolean refresherStarted;

    private ModuleConfig() {
    }

    /** Latest configuration; never blocks, never returns null. */
    public static Snapshot current() {
        return current;
    }

    /** Starts the background reader used inside system_server. Safe to call more than once. */
    public static void start(final ContentResolver resolver) {
        if (resolver == null) {
            return;
        }
        synchronized (ModuleConfig.class) {
            if (refresherStarted) {
                return;
            }
            refresherStarted = true;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                refreshLoop(resolver);
            }
        }, "ppt-flashlight-config");
        thread.setDaemon(true);
        thread.start();
    }

    private static void refreshLoop(ContentResolver resolver) {
        String lastLogged = null;
        while (true) {
            try {
                Snapshot snapshot = read(resolver);
                current = snapshot;
                String text = snapshot.toString();
                if (!text.equals(lastLogged)) {
                    L.i("configuration: " + text);
                    lastLogged = text;
                }
            } catch (Throwable t) {
                L.w("configuration refresh failed: " + t);
            }
            try {
                Thread.sleep(REFRESH_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** One synchronous read (app process, UI). */
    public static Snapshot read(ContentResolver resolver) {
        int holdMs = readInt(resolver, KEY_HOLD_MS, DEFAULT_HOLD_MS);
        if (holdMs <= 0) {
            holdMs = DEFAULT_HOLD_MS;
        }
        return new Snapshot(
                readInt(resolver, KEY_ENABLED, 1) != 0,
                readInt(resolver, KEY_KEYCODE, DEFAULT_KEYCODE),
                readInt(resolver, KEY_SCANCODE, DEFAULT_SCANCODE),
                holdMs,
                readInt(resolver, KEY_SUPPRESS, 0) != 0,
                readInt(resolver, KEY_SYSFS_TORCH, 0) != 0,
                readString(resolver, KEY_SYSFS_PATH, DEFAULT_SYSFS_PATH));
    }

    private static int readInt(ContentResolver resolver, String key, int fallback) {
        String raw = readString(resolver, key, null);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            L.w("ignoring non-numeric value for " + key + ": " + raw);
            return fallback;
        }
    }

    private static String readString(ContentResolver resolver, String key, String fallback) {
        if (resolver == null) {
            return fallback;
        }
        try {
            String value = Settings.Global.getString(resolver, key);
            return value == null || value.length() == 0 ? fallback : value;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Immutable configuration snapshot. */
    public static final class Snapshot {

        public final boolean enabled;
        public final int keyCode;
        public final int scanCode;
        public final int holdMs;
        public final boolean suppress;
        public final boolean sysfsTorch;
        public final String sysfsPath;

        Snapshot(boolean enabled, int keyCode, int scanCode, int holdMs, boolean suppress,
                boolean sysfsTorch, String sysfsPath) {
            this.enabled = enabled;
            this.keyCode = keyCode;
            this.scanCode = scanCode;
            this.holdMs = holdMs;
            this.suppress = suppress;
            this.sysfsTorch = sysfsTorch;
            this.sysfsPath = sysfsPath;
        }

        /**
         * True if this key event is the PPT button.
         *
         * <p>The Android keycode is used when the platform mapped the key; the linux scan code is
         * used as well because a key that is absent from the device's key layout arrives as
         * {@code KEYCODE_UNKNOWN} with its raw scan code intact (AOSP's Generic.kl has no entry
         * for linux key 231).
         */
        public boolean matchesKey(int eventKeyCode, int eventScanCode) {
            if (eventKeyCode != 0 && eventKeyCode == keyCode) {
                return true;
            }
            return eventScanCode != 0 && eventScanCode == scanCode;
        }

        @Override
        public String toString() {
            return "enabled=" + enabled + " keycode=" + keyCode + " scancode=" + scanCode
                    + " holdMs=" + holdMs + " suppress=" + suppress
                    + " sysfsTorch=" + sysfsTorch + " sysfsPath=" + sysfsPath;
        }
    }
}
