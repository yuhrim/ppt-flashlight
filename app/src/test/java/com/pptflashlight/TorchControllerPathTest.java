package com.pptflashlight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TorchControllerPathTest {

    @Test
    public void acceptsTheDeviceFlashNode() {
        assertTrue(TorchController.isValidSysfsPath(
                "/sys/devices/virtual/msm_camera_flash/camera_flash_set/camera_flash"));
    }

    @Test
    public void rejectsShellMetaCharacters() {
        assertFalse(TorchController.isValidSysfsPath("/sys/x; reboot"));
        assertFalse(TorchController.isValidSysfsPath("/sys/x && rm -rf /"));
        assertFalse(TorchController.isValidSysfsPath("/sys/$(id)/torch"));
        assertFalse(TorchController.isValidSysfsPath("/sys/`id`/torch"));
        assertFalse(TorchController.isValidSysfsPath("/sys/a b/torch"));
        assertFalse(TorchController.isValidSysfsPath("/sys/../../etc/passwd"));
    }

    @Test
    public void rejectsPathsOutsideSysfsAndNull() {
        assertFalse(TorchController.isValidSysfsPath("/dev/input/event6"));
        assertFalse(TorchController.isValidSysfsPath("sys/x"));
        assertFalse(TorchController.isValidSysfsPath(null));
    }
}
