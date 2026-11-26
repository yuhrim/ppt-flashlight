package com.pptflashlight;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks {@code com.android.server.input.InputManagerService.interceptKeyBeforeQueueing} inside
 * system_server and turns a long PPT press into one flashlight toggle.
 *
 * <p>Android 7.1.2 declares it as
 * {@code private int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags)}; the native
 * dispatcher calls it from {@code InputDispatcher::notifyKey} for every physical key event
 * <em>before</em> the interactive-state checks, so it also fires with the screen off.
 *
 * <p>The hook is passive by default except for the PPT key: the original method still runs for
 * every other key, and its return value is replaced with 0 (do not pass to user) only for the PPT
 * key while suppression is enabled.
 */
final class SystemHook {

    private static final String INPUT_MANAGER_SERVICE =
            "com.android.server.input.InputManagerService";
    private static final String HOOK_METHOD = "interceptKeyBeforeQueueing";
    private static final int ACTION_PASS_TO_USER = 0;    // no flags set == event not sent to apps

    /** Upper bound for the "which keys do I actually see" diagnostics. */
    private static final int MAX_SEEN_KEYS = 24;

    private static final Set<String> seenKeys = new HashSet<String>();

    private static volatile HoldDetector detector = new HoldDetector(ModuleConfig.DEFAULT_HOLD_MS);

    private static Context systemContext;
    private static boolean broadcastFailureLogged;

    /**
     * Worker that hands the toggle to the app. {@code sendBroadcast} is a synchronous call into
     * ActivityManager; doing it on the input dispatch thread, with the input lock held, can stall
     * key delivery and in the worst case trip the system_server watchdog.
     */
    private static Handler toggleHandler;

    private SystemHook() {
    }

    private static boolean installed;

    static synchronized void install(ClassLoader classLoader) {
        if (installed) {
            L.w("hook already installed, ignoring the second request");
            return;
        }
        installed = true;
        Class<?> ims = XposedHelpers.findClass(INPUT_MANAGER_SERVICE, classLoader);
        Set<XC_MethodHook.Unhook> hooks =
                XposedBridge.hookAllMethods(ims, HOOK_METHOD, new KeyHook());
        if (hooks.isEmpty()) {
            L.e("no " + HOOK_METHOD + " method found in " + INPUT_MANAGER_SERVICE
                    + " - the module cannot work on this ROM");
            return;
        }
        L.i("hooked " + INPUT_MANAGER_SERVICE + "." + HOOK_METHOD + " ("
                + hooks.size() + " overload(s))");
        HandlerThread worker = new HandlerThread("ppt-flashlight-toggle");
        worker.start();
        toggleHandler = new Handler(worker.getLooper());
        ModuleConfig.start(resolver());
        ModuleConfig.Snapshot config = ModuleConfig.current();
        if (config.keyCode == 0 && config.scanCode == 0) {
            L.e("both keycode and scancode are 0; set " + ModuleConfig.KEY_KEYCODE
                    + "/" + ModuleConfig.KEY_SCANCODE + " with 'settings put global'");
        }
    }

    private static final class KeyHook extends XC_MethodHook {

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!(param.args[0] instanceof KeyEvent)) {
                    return;
                }
                KeyEvent event = (KeyEvent) param.args[0];
                int action = event.getAction();
                if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
                    return;
                }

                ModuleConfig.Snapshot config = ModuleConfig.current();
                if (!config.enabled) {
                    return;
                }

                int keyCode = event.getKeyCode();
                int scanCode = event.getScanCode();
                if (!config.matchesKey(keyCode, scanCode)) {
                    noteForeignKey(keyCode, scanCode);
                    return;
                }

                HoldDetector.Result result = dispatch(config, event, action);
                report(result, config, event);

                if (config.suppress) {
                    // Swallow the PPT key so the stock handler does not also act on it.
                    param.setResult(ACTION_PASS_TO_USER);
                }
            } catch (Throwable t) {
                L.e("error while handling a key event", t);
            }
        }
    }

    private static HoldDetector.Result dispatch(ModuleConfig.Snapshot config, KeyEvent event,
            int action) {
        HoldDetector active = detector;
        if (active.thresholdMs() != config.holdMs && !active.isPressed()) {
            active = new HoldDetector(config.holdMs);
            detector = active;
        }
        long uptime = SystemClock.uptimeMillis();
        long eventTime = event.getEventTime();
        if (action == KeyEvent.ACTION_DOWN) {
            return active.onKeyDown(event.getKeyCode(), event.getRepeatCount(), eventTime, uptime);
        }
        return active.onKeyUp(event.getKeyCode(), eventTime, uptime);
    }

    private static void report(HoldDetector.Result result, ModuleConfig.Snapshot config,
            KeyEvent event) {
        switch (result) {
            case SHORT_PRESS:
                L.i("PPT short press ignored (threshold " + config.holdMs + " ms)");
                break;
            case LONG_PRESS_TOGGLE:
                toggleTorch();
                break;
            case STALE_RESET:
                L.w("PPT press older than " + HoldDetector.MAX_HOLD_MS
                        + " ms, state reset without toggling");
                break;
            default:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    L.i("PPT down (keycode " + event.getKeyCode() + ", scanCode "
                            + event.getScanCode() + "), waiting for release");
                }
                break;
        }
    }

    private static void toggleTorch() {
        L.i("PPT held long enough, toggling flashlight (off the input thread)");
        Handler handler = toggleHandler;
        if (handler != null && handler.post(new Runnable() {
            @Override
            public void run() {
                sendToggle();
            }
        })) {
            return;
        }
        sendToggle();
    }

    /** Runs off the input thread: blocks only this worker. */
    private static void sendToggle() {
        if (TorchBridge.toggle()) {
            L.i("waiting for the app process to report the torch result");
        } else if (!broadcastFailureLogged) {
            broadcastFailureLogged = true;
            L.e("could not hand the toggle to " + Const.PACKAGE + " (logged once)");
        }
    }

    /**
     * Logs each distinct non-PPT key once, so the user can find the real keycode of the PPT button
     * if the vendor ROM maps it differently. Never logs volume/power keys repeatedly.
     */
    private static void noteForeignKey(int keyCode, int scanCode) {
        if (seenKeys.size() >= MAX_SEEN_KEYS) {
            return;
        }
        String id = keyCode + "/" + scanCode;
        synchronized (seenKeys) {
            if (!seenKeys.add(id)) {
                return;
            }
        }
        L.i("seen key keycode=" + keyCode + " scanCode=" + scanCode
                + " (not the configured PPT key " + ModuleConfig.KEY_KEYCODE
                + " - set 'settings put global " + ModuleConfig.KEY_KEYCODE + " <keycode>' if this"
                + " is the PPT button)");
    }

    static ContentResolver resolver() {
        Context context = systemContext();
        return context == null ? null : context.getContentResolver();
    }

    /** The system_server context, needed for Settings and for sending the broadcast. */
    static synchronized Context systemContext() {
        if (systemContext != null) {
            return systemContext;
        }
        try {
            Class<?> activityThread = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object current = XposedHelpers.callStaticMethod(activityThread, "currentActivityThread");
            if (current == null) {
                L.e("ActivityThread.currentActivityThread() returned null");
                return null;
            }
            systemContext = (Context) XposedHelpers.callMethod(current, "getSystemContext");
        } catch (Throwable t) {
            L.e("could not obtain the system_server context", t);
        }
        return systemContext;
    }
}
