package com.pptflashlight;

import android.util.Log;

/** Logcat output under one tag, from both system_server and the app process. */
public final class L {

    private L() {
    }

    public static void i(String msg) {
        Log.i(Const.TAG, msg);
    }

    public static void w(String msg) {
        Log.w(Const.TAG, msg);
    }

    public static void e(String msg) {
        Log.e(Const.TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(Const.TAG, msg, t);
    }
}
