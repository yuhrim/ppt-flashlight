package com.pptflashlight;

import android.os.Process;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Xposed entry point, declared in {@code assets/xposed_init}.
 *
 * <p>Xposed calls this for the {@code android} package in <em>every</em> process, because every
 * app process loads the framework. The hook must only be installed once, inside system_server
 * (uid 1000), which is the process that owns the input dispatch thread.
 */
public final class XposedInit implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) {
            return;
        }
        if (Process.myUid() != Process.SYSTEM_UID) {
            return;    // an app process: not our hook target
        }
        L.i("module loaded into system_server (pid " + Process.myPid() + ")");
        try {
            SystemHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            L.e("failed to install the input hook", t);
        }
    }
}
