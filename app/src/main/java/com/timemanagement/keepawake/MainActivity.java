package com.timemanagement.keepawake;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;

public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 101;
    private TextView status, countdown;
    private EditText interval;
    private Button start, stop;
    private int pendingInterval = 5;
    private boolean receiverRegistered = false;
    private boolean waitingForOverlayPermission = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!KeepAwakeService.ACTION_STATE.equals(intent.getAction())) return;
            applyState(
                intent.getBooleanExtra(KeepAwakeService.EXTRA_RUNNING, false),
                intent.getIntExtra(KeepAwakeService.EXTRA_INTERVAL, 5),
                intent.getIntExtra(KeepAwakeService.EXTRA_REMAINING, 5)
            );
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        loadState();
    }

    @Override protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter(KeepAwakeService.ACTION_STATE);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(receiver, f);
            receiverRegistered = true;
        }
        loadState();
    }

    @Override protected void onResume() {
        super.onResume();
        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false;
            if (Settings.canDrawOverlays(this)) {
                continueStartFlow();
            } else {
                toast("Display over other apps permission is required for the keep-awake overlay.");
            }
        }
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(244,246,250));

        TextView title = text("Time Management", 28, Color.rgb(17,24,39), true);
        root.addView(title);

        TextView sub = text("Keep-awake session", 15, Color.rgb(107,114,128), false);
        root.addView(sub, lpTop(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4));

        status = text("READY", 12, Color.rgb(75,85,99), true);
        status.setPadding(dp(14), dp(7), dp(14), dp(7));
        root.addView(status, lpTop(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 22));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(20), dp(24), dp(20), dp(24));
        card.setBackground(round(Color.WHITE, 22));
        card.setElevation(dp(2));
        root.addView(card, lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 18));

        card.addView(text("NEXT CYCLE", 12, Color.rgb(107,114,128), true));
        countdown = text("5", 72, Color.rgb(37,99,235), true);
        card.addView(countdown);
        card.addView(text("seconds", 14, Color.rgb(107,114,128), false));

        TextView lbl = text("Cycle interval", 14, Color.rgb(17,24,39), true);
        root.addView(lbl, lpTop(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 24));

        interval = new EditText(this);
        interval.setText("5");
        interval.setTextSize(16);
        interval.setSingleLine(true);
        interval.setInputType(InputType.TYPE_CLASS_NUMBER);
        interval.setPadding(dp(14),0,dp(14),0);
        interval.setBackground(round(Color.WHITE,14));
        root.addView(interval, lpTop(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), 8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row, lpTop(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), 20));

        start = button("Start", Color.WHITE, Color.rgb(37,99,235));
        stop = button("Stop", Color.rgb(185,28,28), Color.rgb(254,242,242));
        row.addView(start, new LinearLayout.LayoutParams(0, dp(56), 1));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(12),1);
        Space s = new Space(this); row.addView(s,gap);
        row.addView(stop, new LinearLayout.LayoutParams(0, dp(56), 1));

        start.setOnClickListener(v -> startPressed());
        stop.setOnClickListener(v -> stopPressed());

        TextView note = text(
            "A small semi-transparent TM overlay stays above other apps and keeps the screen awake while the session is running. The overlay does not receive taps.",
            12, Color.rgb(107,114,128), false
        );
        root.addView(note, lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 18));
        return root;
    }

    private void startPressed() {
        int value;
        try { value = Integer.parseInt(interval.getText().toString().trim()); }
        catch (Exception e) { toast("Enter 1 to 3600 seconds."); return; }
        if (value < 1 || value > 3600) { toast("Enter 1 to 3600 seconds."); return; }
        pendingInterval = value;

        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = true;
            Intent i = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
            );
            startActivity(i);
            return;
        }
        continueStartFlow();
    }

    private void continueStartFlow() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        startSession(pendingInterval);
    }

    private void startSession(int seconds) {
        Intent i = new Intent(this, KeepAwakeService.class)
            .setAction(KeepAwakeService.ACTION_START)
            .putExtra(KeepAwakeService.EXTRA_INTERVAL, seconds);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        applyState(true, seconds, seconds);
    }

    private void stopPressed() {
        startService(new Intent(this, KeepAwakeService.class).setAction(KeepAwakeService.ACTION_STOP));
        int v = safeInterval();
        applyState(false, v, v);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQ_NOTIFICATIONS && grants.length > 0 &&
            grants[0] == PackageManager.PERMISSION_GRANTED) {
            startSession(pendingInterval);
        } else if (requestCode == REQ_NOTIFICATIONS) {
            toast("Notification permission is required while the background session is active.");
        }
    }

    private void loadState() {
        SharedPreferences p = getSharedPreferences(KeepAwakeService.PREFS, MODE_PRIVATE);
        int i = p.getInt(KeepAwakeService.PREF_INTERVAL, 5);
        applyState(
            p.getBoolean(KeepAwakeService.PREF_RUNNING,false),
            i,
            p.getInt(KeepAwakeService.PREF_REMAINING,i)
        );
    }

    private void applyState(boolean running, int seconds, int remaining) {
        if (interval == null) return;
        interval.setText(String.valueOf(seconds));
        interval.setEnabled(!running);
        countdown.setText(String.valueOf(Math.max(1,remaining)));
        start.setEnabled(!running);
        stop.setEnabled(running);
        status.setText(running ? "RUNNING" : "READY");
        status.setTextColor(running ? Color.rgb(21,128,61) : Color.rgb(75,85,99));
        status.setBackground(round(
            running ? Color.rgb(240,253,244) : Color.rgb(229,231,235), 999
        ));
        if (running) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private int safeInterval() {
        try {
            return Math.max(1, Math.min(3600,
                Integer.parseInt(interval.getText().toString().trim())));
        } catch (Exception e) { return 5; }
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String label, int fg, int bg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(fg);
        b.setBackground(round(bg,16));
        return b;
    }

    private LinearLayout.LayoutParams lpTop(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w,h);
        p.topMargin = dp(top);
        return p;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this,s,Toast.LENGTH_SHORT).show();
    }
}
