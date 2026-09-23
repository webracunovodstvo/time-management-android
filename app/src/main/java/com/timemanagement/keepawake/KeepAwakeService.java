package com.timemanagement.keepawake;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

public class KeepAwakeService extends Service {
    public static final String ACTION_START = "com.timemanagement.keepawake.START";
    public static final String ACTION_STOP = "com.timemanagement.keepawake.STOP";
    public static final String ACTION_STATE = "com.timemanagement.keepawake.STATE";
    public static final String EXTRA_INTERVAL = "interval";
    public static final String EXTRA_REMAINING = "remaining";
    public static final String EXTRA_RUNNING = "running";
    public static final String PREFS = "time_management_state";
    public static final String PREF_RUNNING = "running";
    public static final String PREF_INTERVAL = "interval";
    public static final String PREF_REMAINING = "remaining";

    private static final String CHANNEL = "time_management_active";
    private static final int NOTIFICATION_ID = 41;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private boolean running;
    private int interval = 5;
    private int remaining = 5;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            remaining--;
            if (remaining <= 0) remaining = interval;
            save();
            broadcast();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TimeManagement::WakeLock");
        wakeLock.setReferenceCounted(false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            interval = Math.max(1, Math.min(3600, intent.getIntExtra(EXTRA_INTERVAL,5)));
            remaining = interval;
        } else {
            SharedPreferences p = getSharedPreferences(PREFS,MODE_PRIVATE);
            interval = p.getInt(PREF_INTERVAL,5);
            remaining = Math.max(1,p.getInt(PREF_REMAINING,interval));
        }
        begin();
        return START_STICKY;
    }

    private void begin() {
        running = true;
        if (!wakeLock.isHeld()) wakeLock.acquire();

        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIFICATION_ID,n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
            startForeground(NOTIFICATION_ID,n);

        handler.removeCallbacks(tick);
        handler.postDelayed(tick,1000);
        save();
        broadcast();
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(
            this,0,new Intent(this,MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent stop = PendingIntent.getService(
            this,1,new Intent(this,KeepAwakeService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Time Management")
            .setContentText("Session active • " + interval + " second cycle")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(R.drawable.ic_notification,"Stop",stop).build())
            .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL,"Active session",NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Shown while Time Management is running.");
            c.setSound(null,null);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private void save() {
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putBoolean(PREF_RUNNING,running)
            .putInt(PREF_INTERVAL,interval)
            .putInt(PREF_REMAINING,remaining)
            .apply();
    }

    private void broadcast() {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra(EXTRA_RUNNING,running);
        i.putExtra(EXTRA_INTERVAL,interval);
        i.putExtra(EXTRA_REMAINING,remaining);
        sendBroadcast(i);
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacks(tick);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        save();
        broadcast();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
