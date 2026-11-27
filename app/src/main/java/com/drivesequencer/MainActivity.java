package com.drivesequencer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    static final int BG = GaugeView.BG, PANEL = 0xFF151A1F, LINE = GaugeView.LINE, INK = GaugeView.INK,
            MUTE = GaugeView.MUTE, AMBER = GaugeView.AMBER, GO = GaugeView.GO, STOP = GaugeView.STOP;
    static final int REQ_LOCATION = 1, REQ_SONG = 2;
    // demo drive: (km/h, seconds): pull away, red light, open road, slow down
    static final float[][] DEMO = {{0, 5}, {50, 11}, {50, 5}, {0, 9}, {70, 9}, {105, 12}, {105, 5}, {35, 6}, {0, 9}};

    private final Synth S = Synth.get();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Typeface cond;
    private GaugeView gauge;
    private TextView msg, simVal, topVal, volVal, songName;
    private SeekBar simBar, topBar;
    private Button gpsBtn, simBtn, stopBtn, genBtn, songBtn, mphBtn, kmhBtn;
    private final List<Button> presetBtns = new ArrayList<>();
    private LinearLayout simCard, songCard, presetGrid;
    private boolean mph, keepOn;
    private int demoIndex = -1, shownSrc = -1;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 33);
        }
    };
    private final Runnable demoStep = new Runnable() {
        @Override public void run() {
            if (demoIndex < 0) return;
            if (demoIndex >= DEMO.length) { demoIndex = -1; msg.setText("Demo finished. Drag the slider to keep driving."); return; }
            float[] seg = DEMO[demoIndex++];
            setSim(seg[0]);
            handler.postDelayed(this, (long) (seg[1] * 1000));
        }
    };

    // ---------------- setup ----------------

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("ds", MODE_PRIVATE);
        cond = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        mph = prefs.getBoolean("mph", true);
        S.topKmh = prefs.getFloat("topK", 96.6f);
        S.volume = prefs.getFloat("vol", .8f);
        S.preset = prefs.getInt("preset", 0);
        S.generative = !"trk".equals(prefs.getString("mode", "gen"));
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(build());
        applyUnits();
        applyMode();
        applyPreset();
    }

    @Override protected void onResume() { super.onResume(); handler.post(tick); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(tick); }

    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);
        // keep clear of the status bar and gesture bar on edge-to-edge phones
        scroll.setOnApplyWindowInsetsListener((v, in) -> {
            col.setPadding(pad, pad + in.getSystemWindowInsetTop(), pad, pad + in.getSystemWindowInsetBottom());
            return in;
        });

        // header: title + generative / my song switch
        LinearLayout head = row();
        TextView title = text("Drive Sequencer", 26, INK);
        title.setTypeface(cond);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        genBtn = segBtn("Generative");
        songBtn = segBtn("My song");
        LinearLayout seg = row();
        seg.setBackground(box(PANEL, LINE, 22));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.addView(genBtn);
        seg.addView(songBtn);
        head.addView(seg);
        col.addView(head);
        genBtn.setOnClickListener(v -> setMode("gen"));
        songBtn.setOnClickListener(v -> setMode("trk"));

        gauge = new GaugeView(this);
        col.addView(gauge, lp(dp(6)));

        // presets, two per row
        presetGrid = new LinearLayout(this);
        presetGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout pr = null;
        for (int i = 0; i < Synth.PRESETS.length; i++) {
            if (i % 2 == 0) { pr = row(); presetGrid.addView(pr, lp(i == 0 ? dp(4) : dp(8))); }
            Synth.Preset p = Synth.PRESETS[i];
            SpannableStringBuilder sb = new SpannableStringBuilder(p.name + "\n" + p.desc);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, p.name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(1.25f), 0, p.name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(MUTE), p.name.length() + 1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            Button b = btn("", 13);
            b.setText(sb);
            b.setTypeface(Typeface.DEFAULT);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setPadding(dp(12), dp(10), dp(12), dp(10));
            final int idx = i;
            b.setOnClickListener(v -> { S.preset = idx; prefs.edit().putInt("preset", idx).apply(); applyPreset(); });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i % 2 == 1) l.leftMargin = dp(8);
            pr.addView(b, l);
            presetBtns.add(b);
        }
        col.addView(presetGrid, lp(dp(8)));

        // start buttons
        LinearLayout go = row();
        gpsBtn = btn("Drive with GPS", 19);
        gpsBtn.setTextColor(0xFF111111);
        gpsBtn.setBackground(box(AMBER, 0, 14));
        simBtn = btn("Try simulator", 19);
        go.addView(gpsBtn, new LinearLayout.LayoutParams(0, dp(58), 1));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, dp(58), 1);
        sl.leftMargin = dp(10);
        go.addView(simBtn, sl);
        col.addView(go, lp(dp(14)));
        gpsBtn.setOnClickListener(v -> startGps());
        simBtn.setOnClickListener(v -> startDrive(DriveService.ACT_SIM));

        stopBtn = btn("Stop", 19);
        stopBtn.setTextColor(STOP);
        stopBtn.setBackground(box(BG, STOP, 14));
        stopBtn.setVisibility(View.GONE);
        stopBtn.setOnClickListener(v -> stopDrive());
        col.addView(stopBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)) {{ topMargin = dp(10); }});

        msg = text("", 15, AMBER);
        msg.setGravity(Gravity.CENTER);
        col.addView(msg, lp(dp(10)));

        // simulator card
        simCard = card("Simulator");
        simVal = labelRow(simCard, "Speed you're heading for");
        simBar = slider(simCard, 80);
        simBar.setOnSeekBarChangeListener(new Seek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (!user) return;
                stopDemo();
                S.simTarget = p * f();
                simVal.setText(p + " " + unitName());
            }
        });
        simCard.addView(text("The car speeds up and brakes realistically, so a quick drag up feels like pulling away hard.", 14, MUTE), lp(dp(4)));
        Button demo = btn("Play a demo drive", 16);
        demo.setOnClickListener(v -> startDemo());
        simCard.addView(demo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)) {{ topMargin = dp(10); }});
        simCard.setVisibility(View.GONE);
        col.addView(simCard, lp(dp(14)));

        // my song card
        songCard = card("Your song");
        Button pick = btn("Choose a song", 16);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/*");
            startActivityForResult(i, REQ_SONG);
        });
        songCard.addView(pick, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        songName = text(prefs.getString("trackName", "No song chosen yet. It loops, and speeds up and slows down with you."), 14, MUTE);
        songCard.addView(songName, lp(dp(8)));
        CheckBox keep = new CheckBox(this);
        keep.setText("Keep pitch when the tempo changes");
        keep.setTextColor(INK);
        keep.setTextSize(16);
        keep.setButtonTintList(ColorStateList.valueOf(AMBER));
        keep.setChecked(prefs.getBoolean("keep", true));
        keep.setMinHeight(dp(48));
        keep.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("keep", on).apply());
        songCard.addView(keep, lp(dp(6)));
        col.addView(songCard, lp(dp(14)));

        // settings
        LinearLayout set = card("Settings");
        LinearLayout ur = row();
        ur.addView(text("Units", 16, INK), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mphBtn = segBtn("mph");
        kmhBtn = segBtn("km/h");
        LinearLayout useg = row();
        useg.setBackground(box(BG, LINE, 22));
        useg.setPadding(dp(3), dp(3), dp(3), dp(3));
        useg.addView(mphBtn);
        useg.addView(kmhBtn);
        ur.addView(useg);
        set.addView(ur);
        mphBtn.setOnClickListener(v -> { mph = true; prefs.edit().putBoolean("mph", true).apply(); applyUnits(); });
        kmhBtn.setOnClickListener(v -> { mph = false; prefs.edit().putBoolean("mph", false).apply(); applyUnits(); });
        topVal = labelRow(set, "Full energy at");
        topBar = slider(set, 90);
        topBar.setOnSeekBarChangeListener(new Seek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (!user) return;
                int v = Math.max(minTop(), p);
                S.topKmh = v * f();
                prefs.edit().putFloat("topK", S.topKmh).apply();
                topVal.setText(v + " " + unitName());
            }
        });
        volVal = labelRow(set, "Volume");
        SeekBar vol = slider(set, 100);
        vol.setProgress(Math.round(S.volume * 100));
        volVal.setText(Math.round(S.volume * 100) + "%");
        vol.setOnSeekBarChangeListener(new Seek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                S.volume = p / 100f;
                prefs.edit().putFloat("vol", S.volume).apply();
                volVal.setText(p + "%");
            }
        });
        col.addView(set, lp(dp(14)));

        col.addView(text("Set it up before you drive off; nothing needs touching while you're moving. "
                + "It keeps playing with the screen off and turns down when your sat-nav speaks. "
                + "Stop it from here or from the notification.", 13, MUTE), lp(dp(18)));
        return scroll;
    }

    // ---------------- start / stop ----------------

    private void startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startDrive(DriveService.ACT_GPS);
            return;
        }
        List<String> ask = new ArrayList<>();
        ask.add(Manifest.permission.ACCESS_FINE_LOCATION);
        ask.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) ask.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(ask.toArray(new String[0]), REQ_LOCATION);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_LOCATION) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startDrive(DriveService.ACT_GPS);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            msg.setText("Turn on \"Precise\" location for this app so it can read your speed.");
        } else {
            msg.setText("Location was refused. Allow it in Settings > Apps > Drive Sequencer > Permissions.");
        }
    }

    private void startDrive(String action) {
        if (DriveService.ACT_GPS.equals(action)) stopDemo();
        if (Build.VERSION.SDK_INT >= 33 && DriveService.ACT_SIM.equals(action)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("askedNotif", false)) {
            prefs.edit().putBoolean("askedNotif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
        }
        msg.setText(DriveService.ACT_GPS.equals(action) ? "Waiting for a GPS fix…" : "");
        startForegroundService(new Intent(this, DriveService.class).setAction(action));
    }

    private void stopDrive() {
        stopDemo();
        startService(new Intent(this, DriveService.class).setAction(DriveService.ACT_STOP));
        msg.setText("");
    }

    private void startDemo() {
        if (S.source != Synth.SRC_SIM) startDrive(DriveService.ACT_SIM);
        stopDemo();
        demoIndex = 0;
        msg.setText("Demo: pull away, stop at a red light, open road, slow down.");
        handler.post(demoStep);
    }

    private void stopDemo() {
        demoIndex = -1;
        handler.removeCallbacks(demoStep);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_SONG || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
        String name = "Chosen song";
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) { }
        prefs.edit().putString("track", uri.toString()).putString("trackName", "Loaded: " + name).apply();
        songName.setText("Loaded: " + name);
        if (DriveService.instance != null) DriveService.instance.refreshTrack();
    }

    // ---------------- settings ----------------

    private void setMode(String m) {
        prefs.edit().putString("mode", m).apply();
        S.generative = !"trk".equals(m);
        applyMode();
        if (DriveService.instance != null) DriveService.instance.refreshTrack();
    }

    private void applyMode() {
        boolean gen = S.generative;
        styleSeg(genBtn, gen);
        styleSeg(songBtn, !gen);
        songCard.setVisibility(gen ? View.GONE : View.VISIBLE);
        presetGrid.setVisibility(gen ? View.VISIBLE : View.GONE);
        gauge.showLayers = gen;
    }

    private void applyPreset() {
        for (int i = 0; i < presetBtns.size(); i++) {
            boolean on = i == S.preset;
            presetBtns.get(i).setBackground(box(PANEL, on ? AMBER : LINE, 12));
        }
        gauge.layers = Synth.PRESETS[Math.max(0, Math.min(Synth.PRESETS.length - 1, S.preset))].layers;
    }

    private float f() { return mph ? 1.609344f : 1f; }
    private String unitName() { return mph ? "mph" : "km/h"; }
    private int minTop() { return mph ? 20 : 30; }

    private void applyUnits() {
        styleSeg(mphBtn, mph);
        styleSeg(kmhBtn, !mph);
        topBar.setMax(mph ? 90 : 150);
        int top = Math.round(S.topKmh / f());
        topBar.setProgress(top);
        topVal.setText(top + " " + unitName());
        simBar.setMax(mph ? 80 : 130);
        setSim(S.simTarget);
        gauge.unit = unitName();
    }

    private void setSim(float kmh) {
        S.simTarget = kmh;
        int v = Math.round(kmh / f());
        simBar.setProgress(v);
        simVal.setText(v + " " + unitName());
    }

    // ---------------- live screen ----------------

    private void refresh() {
        int src = S.source;
        boolean running = src != Synth.SRC_NONE;
        gauge.running = running;
        gauge.speed = String.valueOf(Math.round(S.speedOut / f()));
        gauge.energy = S.energyOut;
        gauge.boost = S.boostOut;
        gauge.brake = S.brakeOut;
        gauge.step = running && S.generative ? S.stepOut : -1;
        gauge.mask = S.layerMask;
        if (!running) { gauge.state = "Ready"; gauge.stateColor = MUTE; }
        else if (S.waitingGps) { gauge.state = "Waiting for GPS"; gauge.stateColor = AMBER; }
        else if (S.speedOut < 2) {
            if (S.brakeOut > .2f) { gauge.state = "Rolling to a stop"; gauge.stateColor = AMBER; }
            else { gauge.state = "Stopped · idling"; gauge.stateColor = STOP; }
        } else if (S.boostOut > .35f) { gauge.state = "Pulling away"; gauge.stateColor = GO; }
        else if (S.brakeOut > .35f) { gauge.state = "Braking"; gauge.stateColor = AMBER; }
        else { gauge.state = "Cruising"; gauge.stateColor = GO; }
        if (!running) gauge.sub = "Press a button below to start";
        else if (S.generative) gauge.sub = Math.round(S.bpmOut) + " BPM";
        else gauge.sub = prefs.getString("track", null) == null ? "Choose a song below"
                : String.format("Song speed ×%.2f", Math.max(.5, Math.min(2, .85 + .4 * S.energyOut + .05 * S.boostOut)));
        gauge.update();

        String problem = DriveService.problem;
        if (problem != null) msg.setText(problem);
        else if (src == Synth.SRC_GPS && !S.waitingGps) msg.setText(DriveService.weakGps ? "Weak GPS signal: speed may be jumpy." : "");

        if (src != shownSrc) {
            shownSrc = src;
            stopBtn.setVisibility(running ? View.VISIBLE : View.GONE);
            gpsBtn.setText(src == Synth.SRC_GPS ? "GPS on" : "Drive with GPS");
            simBtn.setText(src == Synth.SRC_SIM ? "Simulating" : "Try simulator");
            simBtn.setBackground(box(PANEL, src == Synth.SRC_SIM ? GO : LINE, 14));
            simCard.setVisibility(src == Synth.SRC_SIM ? View.VISIBLE : View.GONE);
        }
        if (running != keepOn) {
            keepOn = running;
            if (running) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ---------------- little view helpers ----------------

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable box(int fillColor, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fillColor);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1.5f), stroke);
        return g;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = top;
        return l;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    private Button btn(String s, float sp) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(sp);
        b.setTypeface(cond);
        b.setTextColor(INK);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(48));
        b.setBackground(box(PANEL, LINE, 12));
        return b;
    }

    private Button segBtn(String s) {
        Button b = btn(s, 15);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setPadding(dp(14), 0, dp(14), 0);
        return b;
    }

    private void styleSeg(Button b, boolean on) {
        b.setBackground(on ? box(INK, 0, 20) : box(0x00000000, 0, 20));
        b.setTextColor(on ? 0xFF111111 : MUTE);
    }

    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(box(PANEL, LINE, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView t = text(title, 19, INK);
        t.setTypeface(cond);
        c.addView(t);
        return c;
    }

    /** Adds "label ........ value" and returns the value TextView. */
    private TextView labelRow(LinearLayout parent, String label) {
        LinearLayout r = row();
        r.addView(text(label, 16, INK), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = text("", 16, INK);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        r.addView(v);
        parent.addView(r, lp(dp(10)));
        return v;
    }

    private SeekBar slider(LinearLayout parent, int max) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgressTintList(ColorStateList.valueOf(AMBER));
        s.setThumbTintList(ColorStateList.valueOf(AMBER));
        s.setMinimumHeight(dp(44));
        parent.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        return s;
    }

    /** SeekBar listener with only the method we need. */
    abstract static class Seek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }
}
