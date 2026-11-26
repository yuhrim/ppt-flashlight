package com.pptflashlight;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import java.io.IOException;

/**
 * Owns the torch in the module's app process.
 *
 * <p>{@code CameraManager.setTorchMode} keeps the lamp on only while the calling binder lives, so
 * the state is intentionally in memory only: if the process dies the lamp goes out and the next
 * press turns it on again.
 */
public final class TorchController {

    private static final TorchController INSTANCE = new TorchController();

    private enum Mode { OFF, CAMERA2, SYSFS }

    private Mode mode = Mode.OFF;
    private String cameraId;

    private TorchController() {
    }

    public static TorchController get() {
        return INSTANCE;
    }

    public synchronized boolean isOn() {
        return mode != Mode.OFF;
    }

    public synchronized Result toggle(Context context, ModuleConfig.Snapshot config) {
        return isOn() ? turnOff(context, config) : turnOn(context, config);
    }

    public synchronized Result setOff(Context context, ModuleConfig.Snapshot config) {
        return isOn() ? turnOff(context, config) : new Result(false, "already off");
    }

    private Result turnOn(Context context, ModuleConfig.Snapshot config) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return fail("android.permission.CAMERA is not granted to " + Const.PACKAGE);
        }
        String id = findFlashCamera(context);
        if (id != null) {
            try {
                cameraManager(context).setTorchMode(id, true);
                mode = Mode.CAMERA2;
                cameraId = id;
                return new Result(true, "torch on via camera2 (camera id " + id + ")");
            } catch (CameraAccessException e) {
                L.w("setTorchMode(" + id + ", true) failed: " + e.getMessage()
                        + " (code " + e.getReason() + ")");
            } catch (Throwable t) {
                L.e("setTorchMode(" + id + ", true) failed", t);
            }
        } else {
            L.w("no camera reports a flash unit");
        }

        if (config.sysfsTorch) {
            if (writeSysfs(config.sysfsPath, true)) {
                mode = Mode.SYSFS;
                return new Result(true, "torch on via sysfs (experimental)");
            }
            return fail("sysfs torch write failed for " + config.sysfsPath);
        }
        return fail("could not switch the torch on (camera busy, or no flash found)");
    }

    private Result turnOff(Context context, ModuleConfig.Snapshot config) {
        Mode previous = mode;
        String id = cameraId;
        mode = Mode.OFF;
        cameraId = null;

        if (previous == Mode.CAMERA2 && id != null) {
            try {
                cameraManager(context).setTorchMode(id, false);
                return new Result(false, "torch off via camera2");
            } catch (CameraAccessException e) {
                return fail("setTorchMode(" + id + ", false) failed: " + e.getMessage());
            } catch (Throwable t) {
                return fail("setTorchMode(" + id + ", false) failed: " + t);
            }
        }
        if (previous == Mode.SYSFS) {
            writeSysfs(config.sysfsPath, false);
            return new Result(false, "torch off via sysfs");
        }
        return new Result(false, "already off");
    }

    private String findFlashCamera(Context context) {
        try {
            CameraManager manager = cameraManager(context);
            String fallback = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (!Boolean.TRUE.equals(hasFlash)) {
                    continue;
                }
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
                if (fallback == null) {
                    fallback = id;
                }
            }
            return fallback;
        } catch (Throwable t) {
            L.e("could not enumerate cameras", t);
            return null;
        }
    }

    private static CameraManager cameraManager(Context context) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("CameraManager is not available");
        }
        return manager;
    }

    /**
     * Last-resort torch through the sysfs node used by the msm flash driver.
     *
     * <p>Experimental: on the E6810 that node is known to light the LED for a moment, but a stable
     * on/off cycle has not been verified, and writing it needs root ({@code su}). Off unless
     * {@code ppt_flashlight_sysfs_torch=1}.
     */
    private boolean writeSysfs(String path, boolean on) {
        if (!isValidSysfsPath(path)) {
            L.e("refusing to write suspicious sysfs path: " + path);
            return false;
        }
        String command = "echo " + (on ? "1" : "0") + " > " + path;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[] {"su", "-c", command});
            int exit = process.waitFor();
            if (exit == 0) {
                L.i("sysfs torch " + (on ? "on" : "off") + " written to " + path);
                return true;
            }
            L.e("sysfs write exited with " + exit + " (" + command + ")");
        } catch (IOException e) {
            L.e("sysfs write could not run su: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return false;
    }

    /** The node path is passed to a shell, so only plain path characters are accepted. */
    static boolean isValidSysfsPath(String path) {
        return path != null && path.matches("/sys/[A-Za-z0-9/_.:-]+") && !path.contains("..");
    }

    private static Result fail(String message) {
        L.e(message);
        return new Result(false, message);
    }

    /** Outcome of one torch operation. */
    public static final class Result {

        public final boolean on;
        public final String detail;

        Result(boolean on, String detail) {
            this.on = on;
            this.detail = detail;
        }
    }
}
