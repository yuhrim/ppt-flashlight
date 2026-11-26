package com.pptflashlight;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives the toggle request from the system_server hook. This runs in the module's own app
 * process, which is what actually owns the torch.
 */
public final class TorchReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Const.ACTION_TOGGLE.equals(action)) {
            L.i("toggle request received from system_server");
            TorchService.enqueue(context, TorchService.CMD_TOGGLE);
        } else if (Const.ACTION_OFF.equals(action)) {
            L.i("torch-off request received from system_server");
            TorchService.enqueue(context, TorchService.CMD_OFF);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // Proof in logcat that the module is loaded again after a reboot.
            L.i("boot completed - PPT Flashlight is active");
        }
    }
}
