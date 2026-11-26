package com.pptflashlight;

/** Names shared by the system_server hook and the app process. Must match the manifest. */
public final class Const {

    public static final String TAG = "PPTFlashlight";

    public static final String PACKAGE = "com.pptflashlight";
    public static final String RECEIVER = PACKAGE + ".TorchReceiver";

    /** Hook -> app process: flip the torch. */
    public static final String ACTION_TOGGLE = PACKAGE + ".action.TOGGLE_TORCH";
    /** Hook -> app process: make sure the torch is off. */
    public static final String ACTION_OFF = PACKAGE + ".action.TORCH_OFF";

    private Const() {
    }
}
