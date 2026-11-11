package com.crbyob.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** Foreground service hosting the bridge; holds the Wi-Fi multicast/high-perf locks and a partial wake lock. */
public final class BridgeService extends Service {
    public static final String ACTION_START = "com.crbyob.bridge.START";
    public static final String ACTION_STOP = "com.crbyob.bridge.STOP";
    static final String ACTION_USB_PERMISSION = "com.crbyob.bridge.USB_PERMISSION";
    private static final String CHANNEL = "bridge";
    private static final int NOTIF_ID = 1;

    static volatile Bridge bridge;
    private WifiManager.MulticastLock mcLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;

    /** Start in progress (Bridge.start still running) / Stop in progress (Bridge.stop or teardown still running). */
    static volatile boolean starting, stopping;
    /** Start/Stop commands handled so far: the dashboard knows its tap was picked up once this moves. */
    static volatile int commands;

    public static Bridge current() {
        return bridge;
    }

    /** True while Start or Stop is still finishing, so the dashboard can show a spinner instead of a dead button. */
    static boolean switching() {
        return starting || stopping;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            BLog.log("usb_broadcast", "action", a, "dev", d == null ? null : d.getDeviceName(),
                    "granted", granted);
            final Bridge b = bridge;
            if (b == null) return;
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                new Thread(() -> b.onDetached(d), "usb-detach").start();
            } else if (ACTION_USB_PERMISSION.equals(a) && !granted) {
                b.permissionPending = 0;
                BLog.log("usb_permission_denied", "dev", d == null ? null : d.getDeviceName(),
                        "hint", "accept the USB authorization in the headset or tap to retry");
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a) || ACTION_USB_PERMISSION.equals(a)) {
                if (ACTION_USB_PERMISSION.equals(a)) b.permissionPending = 0;
                // give the device a moment to settle (USB2 -> USB3 re-enumeration)
                main.postDelayed(() -> new Thread(() -> {
                    if (d != null) b.onAttached(d); else b.scanUsb();
                }, "usb-attach").start(), 500);
            }
        }
    };

    static void requestPermission(Context ctx, UsbManager usb, UsbDevice d) {
        try {
            Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(ctx.getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, flags);
            BLog.log("usb_request_permission", "dev", d.getDeviceName(),
                    "vid", String.format("%04x", d.getVendorId()), "pid", String.format("%04x", d.getProductId()));
            usb.requestPermission(d, pi);
        } catch (Exception e) {
            BLog.log("usb_request_permission_error", "dev", d == null ? null : d.getDeviceName(), "err", String.valueOf(e));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BLog.init(this);
        CrashRecord.install(this);
        MirrorSession.get(this);        // so the bridge's broadcast watcher can auto-connect the mirror
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        commands++;                              // main thread only; the flags below change in this same message
        if (ACTION_STOP.equals(action)) {
            // Stop the bridge on a worker thread, not in onDestroy: joining it there blocked the main thread (and so
            // the whole UI) for as long as the teardown took. The service goes away once the bridge is down.
            final Bridge b = bridge;
            bridge = null;                       // the UI and the USB receiver see "off" right away
            if (b == null) {
                if (!stopping) { stopForeground(true); stopSelf(); }
                return START_NOT_STICKY;
            }
            stopping = true;
            final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
            final Runnable finish = () -> {
                if (done.compareAndSet(false, true)) { stopForeground(true); stopSelf(); }   // onDestroy clears `stopping`
            };
            new Thread(() -> {
                try {
                    b.stop();
                } finally {
                    main.post(finish);
                }
            }, "bridge-stop").start();
            main.postDelayed(() -> {             // same 3 s cap the old join had, so a stuck teardown can't hang Stop
                if (!done.get()) BLog.log("bridge_stop_slow", "waited_ms", 3000);
                finish.run();
            }, 3000);
            return START_NOT_STICKY;
        }
        goForeground();                          // first: startForegroundService() requires it even for an ignored Start
        if (stopping) {                          // the button is disabled while stopping; ignore a stray Start
            BLog.log("start_ignored", "why", "stop in progress");
            return START_NOT_STICKY;
        }
        if (bridge == null) {
            acquireLocks();
            registerUsb();
            Bridge b = new Bridge(this);
            bridge = b;
            starting = true;
            new Thread(() -> {
                try {
                    b.start();
                } catch (Exception e) {
                    // Bridge.start already stopped what it started and put the reason in the dashboard's events
                    BLog.log("bridge_start_failed", "err", String.valueOf(e));
                    main.post(() -> { stopForeground(true); stopSelf(); });
                } finally {
                    starting = false;
                }
            }, "bridge-start").start();
        } else {
            final Bridge b = bridge;
            new Thread(b::scanUsb, "usb-rescan").start();
        }
        return START_STICKY;
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, BridgeService.class).setAction(ACTION_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("CR-BYOB")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stop", ps).build())
                .build();
    }

    private String lastNotif;

    /** Live one-line summary in the notification ("Streaming 15/15/15 fps · Wi-Fi Direct · 1 PC"). */
    private final Runnable notifTick = new Runnable() {
        @Override
        public void run() {
            Bridge b = bridge;
            if (b == null) return;
            String t = b.dash.snap.notif;
            if (t != null && !t.equals(lastNotif)) {
                lastNotif = t;
                getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(t));
            }
            main.postDelayed(this, 2000);
        }
    };

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "CR-BYOB bridge", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = buildNotification(lastNotif != null ? lastNotif : "Starting…");
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIF_ID, n);
        main.removeCallbacks(notifTick);
        main.postDelayed(notifTick, 2000);
    }

    private void acquireLocks() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mcLock = wm.createMulticastLock("CRBYOB");
        mcLock.setReferenceCounted(false);
        mcLock.acquire();
        @SuppressWarnings("deprecation")
        int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        wifiLock = wm.createWifiLock(mode, "CRBYOB");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CRBYOB:bridge");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        BLog.log("locks_acquired", "multicast", mcLock.isHeld(), "wifi_high_perf", wifiLock.isHeld(), "wake", wakeLock.isHeld());
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")   // the flag is passed on 33+, where it exists
    private void registerUsb() {
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(ACTION_USB_PERMISSION);
        // NOT_EXPORTED: the system's USB attach/detach broadcasts and our own permission PendingIntent still arrive;
        // other apps can no longer send a fake USB_PERMISSION result
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, f);
        receiverRegistered = true;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(notifTick);
        Bridge b = bridge;
        bridge = null;
        if (b != null) {
            Thread t = new Thread(b::stop, "bridge-stop");
            t.start();
            try { t.join(3000); } catch (InterruptedException ignored) { }
        }
        if (receiverRegistered) {
            try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
            receiverRegistered = false;
        }
        if (mcLock != null && mcLock.isHeld()) mcLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        BLog.log("service_destroyed");
        stopping = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
