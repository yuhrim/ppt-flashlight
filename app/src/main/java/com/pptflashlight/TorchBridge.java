package com.pptflashlight;

import android.content.Context;
import android.content.Intent;

/**
 * Sends the toggle request from system_server to the module's own app process.
 *
 * <p>The flashlight is deliberately <em>not</em> switched from system_server: the torch client
 * binder has to stay alive for as long as the lamp is on, and {@code setTorchMode} is meant to be
 * called by an app holding {@code android.permission.CAMERA}. This is the same division of labour
 * Physical Button Master Control uses (hook in system_server, torch in the module process).
 */
final class TorchBridge {

    private TorchBridge() {
    }

    static boolean toggle() {
        Context context = SystemHook.systemContext();
        if (context == null) {
            L.e("no system_server context, cannot reach the torch service");
            return false;
        }
        Intent intent = new Intent(Const.ACTION_TOGGLE);
        intent.setClassName(Const.PACKAGE, Const.RECEIVER);
        // The app process is usually cold: wake it and do not skip a stopped package.
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
        try {
            context.sendBroadcast(intent);
            L.i("toggle broadcast sent to " + Const.RECEIVER);
            return true;
        } catch (Throwable t) {
            L.e("could not send the toggle broadcast", t);
            return false;
        }
    }
}
