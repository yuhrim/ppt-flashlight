package com.pptflashlight;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Does the torch work off the main thread and keeps the process alive (foreground) while the lamp
 * is on, because the torch client binder must outlive the request.
 */
public final class TorchService extends Service {

    static final int CMD_OFF = 0;
    static final int CMD_TOGGLE = 1;
    private static final String EXTRA_COMMAND = "command";
    private static final int NOTIFICATION_ID = 0x5054;

    private ExecutorService worker;

    static void enqueue(Context context, int command) {
        Intent intent = new Intent(context, TorchService.class);
        intent.putExtra(EXTRA_COMMAND, command);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        worker = Executors.newSingleThreadExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, final int startId) {
        final int command = intent == null ? CMD_OFF : intent.getIntExtra(EXTRA_COMMAND, CMD_OFF);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    handle(command, startId);
                } catch (Throwable t) {
                    L.e("torch operation failed", t);
                }
            }
        });
        return START_NOT_STICKY;
    }

    private void handle(int command, int startId) {
        ModuleConfig.Snapshot config = ModuleConfig.read(getContentResolver());
        TorchController torch = TorchController.get();
        boolean wasOn = torch.isOn();

        TorchController.Result result = command == CMD_TOGGLE
                ? torch.toggle(this, config)
                : torch.setOff(this, config);

        L.i("torch " + (result.on ? "ON" : "OFF") + ": " + result.detail
                + (result.on == wasOn ? " (state unchanged)" : ""));

        if (result.on) {
            startForeground(NOTIFICATION_ID, buildNotification());
        } else {
            stopForeground(true);
            stopSelf(startId);
        }
    }

    private Notification buildNotification() {
        PendingIntent content = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Flashlight on")
                .setContentText("Hold PPT to turn it off")
                .setOngoing(true)
                .setContentIntent(content)
                .build();
    }

    @Override
    public void onDestroy() {
        if (worker != null) {
            worker.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
