package com.pptflashlight;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** Status and calibration screen. The module itself lives in system_server. */
public final class MainActivity extends Activity {

    private static final int REQUEST_CAMERA = 1;

    private TextView status;
    private TextView hint;
    private CheckBox cbEnabled;
    private CheckBox cbSuppress;
    private CheckBox cbSysfs;
    private EditText etKeycode;
    private EditText etScanCode;
    private EditText etHold;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = (TextView) findViewById(R.id.status);
        hint = (TextView) findViewById(R.id.hint);
        cbEnabled = (CheckBox) findViewById(R.id.cbEnabled);
        cbSuppress = (CheckBox) findViewById(R.id.cbSuppress);
        cbSysfs = (CheckBox) findViewById(R.id.cbSysfs);
        etKeycode = (EditText) findViewById(R.id.etKeycode);
        etScanCode = (EditText) findViewById(R.id.etScanCode);
        etHold = (EditText) findViewById(R.id.etHold);

        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        findViewById(R.id.btnTest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testTorch();
            }
        });

        hint.setText("The hook runs inside system_server. Check it with:\n\n"
                + "adb logcat -s " + Const.TAG + "\n\n"
                + "Expected after a long press: \"PPT down\", \"PPT held long enough\", then the"
                + " torch result. If the PPT key is not recognized, look for the \"seen key"
                + " keycode=... scanCode=...\" line and enter those numbers here.");

        requestCameraIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        refreshStatus();
    }

    private void requestCameraIfNeeded() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_CAMERA) {
            refreshStatus();
        }
    }

    private void load() {
        ModuleConfig.Snapshot config = ModuleConfig.read(getContentResolver());
        cbEnabled.setChecked(config.enabled);
        cbSuppress.setChecked(config.suppress);
        cbSysfs.setChecked(config.sysfsTorch);
        etKeycode.setText(Integer.toString(config.keyCode));
        etScanCode.setText(Integer.toString(config.scanCode));
        etHold.setText(Integer.toString(config.holdMs));
    }

    private void refreshStatus() {
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        String torch = TorchController.get().isOn() ? "on" : "off";
        status.setText("Torch: " + torch + "\nCamera permission: "
                + (camera ? "granted" : "MISSING - the torch needs it")
                + "\nXposed activation cannot be detected from inside the app;"
                + " verify it in logcat.");
    }

    private void save() {
        ModuleConfig.Snapshot current = ModuleConfig.read(getContentResolver());
        int keyCode = parseInt(etKeycode, current.keyCode);
        int scanCode = parseInt(etScanCode, current.scanCode);
        int holdMs = parseInt(etHold, current.holdMs);
        if (holdMs <= 0) {
            etHold.setText(Integer.toString(current.holdMs));
            toast("Hold threshold must be a positive number of milliseconds");
            return;
        }

        StringBuilder failures = new StringBuilder();
        int ok = 0;
        ok += writeBoolean(ModuleConfig.KEY_ENABLED, cbEnabled.isChecked(), failures);
        ok += writeBoolean(ModuleConfig.KEY_SUPPRESS, cbSuppress.isChecked(), failures);
        ok += writeBoolean(ModuleConfig.KEY_SYSFS_TORCH, cbSysfs.isChecked(), failures);
        ok += writeInt(ModuleConfig.KEY_KEYCODE, keyCode, failures);
        ok += writeInt(ModuleConfig.KEY_SCANCODE, scanCode, failures);
        ok += writeInt(ModuleConfig.KEY_HOLD_MS, holdMs, failures);

        load();
        refreshStatus();

        if (failures.length() == 0) {
            toast("Saved (" + ok + " values)");
        } else {
            toast("Could not write: " + failures.toString().trim()
                    + "\nRun the 'settings put global' commands over adb instead.");
        }
    }

    private int writeInt(String key, int value, StringBuilder failures) {
        String how = ConfigWriter.putInt(this, key, value);
        if (how == null) {
            if (failures.length() > 0) {
                failures.append(", ");
            }
            failures.append(key);
            return 0;
        }
        L.i("wrote " + key + "=" + value + " via " + how);
        return 1;
    }

    private int writeBoolean(String key, boolean value, StringBuilder failures) {
        return writeInt(key, value ? 1 : 0, failures);
    }

    private static int parseInt(EditText field, int fallback) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            field.setText(Integer.toString(fallback));
            return fallback;
        }
    }

    private void testTorch() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraIfNeeded();
            toast("Grant the camera permission first");
            return;
        }
        L.i("test toggle requested from the UI");
        TorchService.enqueue(this, TorchService.CMD_TOGGLE);
        // The service works on a background thread; the state is re-read on the next resume.
        status.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshStatus();
            }
        }, 500);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
