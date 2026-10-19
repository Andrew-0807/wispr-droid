package dev.wispr;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.util.Arrays;

/** Hold Right Ctrl to record, release to transcribe with whisper.cpp (Rust) and paste into the focused field. */
public class Svc extends AccessibilityService {
    static { System.loadLibrary("wispr"); }
    static native String transcribe(String model, float[] pcm);

    static final String TAG = "wispr";
    static final int RATE = 16000;

    private volatile boolean rec;
    private Thread worker;
    private AudioManager am;
    private AudioFocusRequest focus;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onServiceConnected() {
        am = getSystemService(AudioManager.class);
        // Transient focus pauses music while recording, and the player resumes when focus is abandoned.
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("w", "Wispr", NotificationManager.IMPORTANCE_MIN));
        try {
            startForeground(1, new Notification.Builder(this, "w")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Wispr: hold Right Ctrl").build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_CTRL_RIGHT) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) start();
        else if (e.getAction() == KeyEvent.ACTION_UP) rec = false;
        return true;
    }

    private void start() {
        if (worker != null && worker.isAlive()) return;
        rec = true;
        worker = new Thread(this::record, "wispr");
        worker.start();
    }

    private void record() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, min * 4);
        am.requestAudioFocus(focus);
        float[] pcm = new float[RATE * 10];
        int n = 0;
        ar.startRecording();
        while (rec) {
            if (n == pcm.length) pcm = Arrays.copyOf(pcm, n * 2);
            int r = ar.read(pcm, n, Math.min(RATE / 10, pcm.length - n), AudioRecord.READ_BLOCKING);
            if (r <= 0) break;
            n += r;
        }
        ar.stop();
        ar.release();
        am.abandonAudioFocusRequest(focus);

        float peak = 0;
        for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs(pcm[i]));
        Log.i(TAG, "recorded " + n + " samples, peak " + peak);
        if (n < RATE / 2 || peak < 0.01f) return;
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.FileOutputStream(new File(getExternalFilesDir(null), "last.f32")))) {
            for (int i = 0; i < n; i++) o.writeFloat(pcm[i]); // TMP debug dump, big endian
        } catch (Exception ignored) {}

        long t = System.currentTimeMillis();
        String text;
        try {
            text = transcribe(new File(getExternalFilesDir(null), "model.bin").getPath(), Arrays.copyOf(pcm, n));
        } catch (RuntimeException ex) {
            Log.e(TAG, "transcribe failed", ex);
            return;
        }
        Log.i(TAG, "transcribed in " + (System.currentTimeMillis() - t) + " ms: " + text);
        if (text == null || text.isEmpty()) return;
        main.post(() -> paste(text));
    }

    private void paste(String text) {
        // The text also stays on the clipboard, so a field that refuses ACTION_PASTE can still take Ctrl+V.
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("wispr", text));
        AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null || !f.performAction(AccessibilityNodeInfo.ACTION_PASTE)) Log.w(TAG, "paste refused, text left on clipboard");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
}
