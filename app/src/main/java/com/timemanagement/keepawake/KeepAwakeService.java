package com.timemanagement.keepawake;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

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
    private WindowManager windowManager;
    private View overlayView;

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
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TimeManagement::WakeLock"
        );
        wakeLock.setReferenceCounted(false);

        windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            interval = Math.max(1, Math.min(
                3600,
                intent.getIntExtra(EXTRA_INTERVAL,5)
            ));
            remaining = interval;
        } else {
            SharedPreferences p = getSharedPreferences(PREFS,MODE_PRIVATE);
            interval = p.getInt(PREF_INTERVAL,5);
            remaining = Math.max(
                1,
                p.getInt(PREF_REMAINING,interval)
            );
        }

        begin();
        return START_STICKY;
    }

    private void begin() {
        running = true;

        if (!wakeLock.isHeld()) wakeLock.acquire();

        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID,n);
        }

        showOverlay();

        handler.removeCallbacks(tick);
        handler.postDelayed(tick,1000);
        save();
        broadcast();
    }

    private void showOverlay() {
        if (overlayView != null || !Settings.canDrawOverlays(this)) return;

        TextView badge = new TextView(this);
        badge.setText("TM");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(11);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setAlpha(0.55f);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(37,99,235));
        bg.setCornerRadius(dp(12));
        badge.setBackground(bg);

        int type = Build.VERSION.SDK_INT >= 26
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            dp(42),
            dp(26),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        );

        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(12);
        lp.y = dp(72);

        try {
            windowManager.addView(badge, lp);
            overlayView = badge;
        } catch (Exception ignored) {
            overlayView = null;
        }
    }

    private void removeOverlay() {
        if (overlayView == null) return;
        try {
            windowManager.removeView(overlayView);
        } catch (Exception ignored) {}
        overlayView = null;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(
            this,
            0,
            new Intent(this,MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent stop = PendingIntent.getService(
            this,
            1,
            new Intent(this,KeepAwakeService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Time Management")
            .setContentText("Keep-awake overlay active • " + interval + " second cycle")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(
                R.drawable.ic_notification,
                "Stop",
                stop
            ).build())
            .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                CHANNEL,
                "Active session",
                NotificationManager.IMPORTANCE_LOW
            );
            c.setDescription("Shown while Time Management is running.");
            c.setSound(null,null);
            getSystemService(NotificationManager.class)
                .createNotificationChannel(c);
        }
    }

    private void save() {
        getSharedPreferences(PREFS,MODE_PRIVATE)
            .edit()
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

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacks(tick);
        removeOverlay();

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        save();
        broadcast();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
