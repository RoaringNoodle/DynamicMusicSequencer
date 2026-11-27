package com.drivesequencer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Runs in the foreground so the music keeps going with the screen off or behind your sat-nav.
 * Reads GPS, feeds the speed to the synth, and streams the synth to the speaker.
 */
public class DriveService extends Service implements LocationListener, AudioManager.OnAudioFocusChangeListener {
    public static final String ACT_GPS = "com.drivesequencer.GPS";
    public static final String ACT_SIM = "com.drivesequencer.SIM";
    public static final String ACT_STOP = "com.drivesequencer.STOP";
    private static final String CHANNEL = "drive";

    static DriveService instance;
    static volatile boolean weakGps = false;
    static volatile String problem = null;

    private final Synth synth = Synth.get();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private PowerManager.WakeLock wake;
    private volatile boolean audioRun;
    private boolean gpsOn;
    private Location lastLoc;
    private MediaPlayer player;
    private Uri playerUri;
    private float lastRate = 1f;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updatePlayer();
            handler.postDelayed(this, 250);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("ds", MODE_PRIVATE);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null || ACT_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        boolean gps = ACT_GPS.equals(action) && hasLocation();
        goForeground(gps);
        problem = null;
        if (gps) startGps(); else stopGps();
        synth.source = gps ? Synth.SRC_GPS : Synth.SRC_SIM;
        startAudio();
        refreshTrack();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_NOT_STICKY;
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void goForeground(boolean gps) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Music playing", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, DriveService.class).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Drive Sequencer")
                .setContentText(gps ? "Music is following your speed" : "Simulator is playing")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((Icon) null, "Stop", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (gps) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            startForeground(1, n, type);
        } else {
            startForeground(1, n);
        }
    }

    // ---------- GPS ----------

    private void startGps() {
        if (gpsOn) return;
        lastLoc = null;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
            gpsOn = true;
        } catch (SecurityException | IllegalArgumentException e) {
            problem = "Couldn't start GPS on this phone.";
        }
    }

    private void stopGps() {
        if (!gpsOn) return;
        ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(this);
        gpsOn = false;
    }

    @Override public void onLocationChanged(Location loc) {
        float kmh;
        if (loc.hasSpeed()) {
            kmh = loc.getSpeed() * 3.6f;
        } else if (lastLoc != null) {
            double dt = (loc.getElapsedRealtimeNanos() - lastLoc.getElapsedRealtimeNanos()) / 1e9;
            kmh = dt > 0 ? (float) (loc.distanceTo(lastLoc) / dt * 3.6) : 0f;
        } else {
            lastLoc = loc;
            return;
        }
        lastLoc = loc;
        if (kmh < 3f) kmh = 0f; // GPS drifts a little when parked
        weakGps = loc.hasAccuracy() && loc.getAccuracy() > 60f;
        synth.pushGps(kmh);
    }

    // Older Android versions call these, so they must exist.
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { problem = "Location is turned off in your phone's settings."; }

    // ---------- audio ----------

    private AudioAttributes attrs() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    private void startAudio() {
        if (audioRun) return;
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs())
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(this, handler)
                .build();
        audio.requestAudioFocus(focus);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "drivesequencer:audio");
        wake.acquire(6 * 60 * 60 * 1000L);
        audioRun = true;
        Thread t = new Thread(this::audioLoop, "synth");
        t.start();
    }

    private void audioLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        int frames = 1024;
        int min = AudioTrack.getMinBufferSize(Synth.SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attrs())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(Synth.SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(Math.max(min, frames * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        short[] buf = new short[frames * 2];
        track.play();
        while (audioRun) {
            synth.render(buf, frames);
            track.write(buf, 0, buf.length);
        }
        track.stop();
        track.release();
    }

    @Override public void onAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_GAIN) setDuck(1f);
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) setDuck(.25f); // sat-nav speaking
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) setDuck(0f);             // phone call
        else if (change == AudioManager.AUDIOFOCUS_LOSS) shutdown();                      // another music app took over
    }

    private void setDuck(float d) {
        synth.duck = d;
        updatePlayer();
    }

    // ---------- "My song" mode ----------

    /** Starts, swaps or stops the user's song to match the saved settings. */
    void refreshTrack() {
        boolean songMode = "trk".equals(prefs.getString("mode", "gen"));
        String u = prefs.getString("track", null);
        synth.generative = !songMode;
        if (!songMode || u == null || synth.source == Synth.SRC_NONE) { releasePlayer(); return; }
        Uri uri = Uri.parse(u);
        if (player != null && uri.equals(playerUri)) {
            if (!player.isPlaying()) player.start();
            return;
        }
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(attrs());
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.start();
            playerUri = uri;
            lastRate = 1f;
            updatePlayer();
        } catch (Exception e) {
            releasePlayer();
            problem = "Couldn't play that song. Try choosing it again.";
        }
    }

    private void updatePlayer() {
        if (player == null) return;
        try {
            float vol = synth.volume * synth.duck;
            player.setVolume(vol, vol);
            if (!player.isPlaying()) return;
            float rate = (float) Synth.clamp(.85 + .4 * synth.energyOut + .05 * synth.boostOut, .5, 2);
            if (Math.abs(rate - lastRate) > .015f) {
                boolean keep = prefs.getBoolean("keep", true);
                player.setPlaybackParams(player.getPlaybackParams().setSpeed(rate).setPitch(keep ? 1f : rate));
                lastRate = rate;
            }
        } catch (Exception ignored) {
        }
    }

    private void releasePlayer() {
        if (player == null) return;
        try { player.release(); } catch (Exception ignored) { }
        player = null;
        playerUri = null;
    }

    // ---------- stop ----------

    private void shutdown() {
        synth.source = Synth.SRC_NONE;
        stopGps();
        handler.removeCallbacks(tick);
        releasePlayer();
        audioRun = false;
        if (focus != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (audioRun) shutdown();
        instance = null;
        super.onDestroy();
    }
}
