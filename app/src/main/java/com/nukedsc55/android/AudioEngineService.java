package com.nukedsc55.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;

public final class AudioEngineService extends Service {
    public static final String ACTION_START = "com.nukedsc55.android.START";
    public static final String ACTION_STOP = "com.nukedsc55.android.STOP";
    public static final String EXTRA_ROM_DIR = "romDir";
    public static final String EXTRA_MODEL = "model";
    public static final String EXTRA_OVERSAMPLING = "oversampling";

    public static volatile boolean running;
    public static volatile String status = "未启动";

    private volatile boolean audioLoopRunning;
    private Thread audioThread;
    private AudioTrack audioTrack;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                "sc55_audio", getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopEngine();
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = new Notification.Builder(this, "sc55_audio")
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_running))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        startForeground(55, notification);

        stopEngine();
        String error = NativeBridge.load(
                intent.getStringExtra(EXTRA_ROM_DIR),
                intent.getStringExtra(EXTRA_MODEL),
                intent.getBooleanExtra(EXTRA_OVERSAMPLING, false));
        if (!error.isEmpty()) {
            status = error;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        int sourceRate = NativeBridge.sampleRate();
        AudioManager audioManager = getSystemService(AudioManager.class);
        int sampleRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        String reportedRate = audioManager == null ? null
                : audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        try {
            if (reportedRate != null) sampleRate = Integer.parseInt(reportedRate);
        } catch (NumberFormatException ignored) {}
        if (sampleRate < 8000) sampleRate = 48000;
        NativeBridge.setOutputSampleRate(sampleRate);

        int minBytes = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        final int burstFrames = 192;
        int bufferBytes = Math.max(minBytes > 0 ? minBytes : 0, burstFrames * 4 * 2);
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();

        audioLoopRunning = true;
        running = true;
        status = "运行中 · " + sourceRate + " → " + sampleRate + " Hz";
        audioTrack.play();
        audioThread = new Thread(this::runAudio, "SC55-Audio");
        audioThread.start();
        return START_STICKY;
    }

    private void runAudio() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        final int frames = 192;
        short[] samples = new short[frames * 2];
        long nextStatusUpdate = SystemClock.elapsedRealtime() + 1000;
        while (audioLoopRunning) {
            int rendered = NativeBridge.render(samples, frames);
            if (rendered <= 0) break;
            int offset = 0;
            int count = rendered * 2;
            while (audioLoopRunning && offset < count) {
                int written = audioTrack.write(samples, offset, count - offset, AudioTrack.WRITE_BLOCKING);
                if (written < 0) {
                    status = "音频输出错误: " + written;
                    audioLoopRunning = false;
                    break;
                }
                offset += written;
            }
            long now = SystemClock.elapsedRealtime();
            if (now >= nextStatusUpdate) {
                long nativeUnderruns = NativeBridge.underrunCount();
                int trackUnderruns = audioTrack.getUnderrunCount();
                status = nativeUnderruns + trackUnderruns == 0
                        ? "低延迟运行中 · " + audioTrack.getSampleRate() + " Hz"
                        : "运行中 · 欠载 " + (nativeUnderruns + trackUnderruns) + " 次";
                nextStatusUpdate = now + 1000;
            }
        }
    }

    private void stopEngine() {
        audioLoopRunning = false;
        if (audioTrack != null) {
            try { audioTrack.pause(); audioTrack.flush(); } catch (IllegalStateException ignored) {}
        }
        if (audioThread != null) {
            try { audioThread.join(800); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
        }
        if (audioTrack != null) {
            try { audioTrack.stop(); } catch (IllegalStateException ignored) {}
            audioTrack.release();
            audioTrack = null;
        }
        NativeBridge.unload();
        running = false;
        if (!status.startsWith("音频") && !status.contains("ROM")) status = "已停止";
    }

    @Override public void onDestroy() {
        stopEngine();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
