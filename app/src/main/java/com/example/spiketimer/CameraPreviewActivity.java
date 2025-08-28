package com.example.spiketimer;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.spiketimer.camera.ImageProxyExt;
import com.example.spiketimer.ui.OverlayView;
import com.example.spiketimer.yolo.TFLiteYoloDetector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CameraPreviewActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "spike_prefs";
    private static final String KEY_COUNTDOWN = "countdown_time";      // giây (float)
    private static final String KEY_NOTIFICATION = "notification_time"; // giây (float)
    private static final int REQUEST_CAMERA_PERMISSION = 1001;

    // Detect params
    private static final float CONF_TH = 0.74f;     // precision-first
    private static final float IOU_TH = 0.45f;
    private static final int   TOP_K   = 10;
    private static final int   MIN_STABLE_FRAMES = 3; // số khung liên tiếp để kích hoạt

    private PreviewView previewView;
    private OverlayView overlay; // View để vẽ khung
    private TextView tvCountdownLabel;
    private TextView tvSeconds;
    private Button btn_reset;
    private ImageButton btnBack;

    // Countdown bằng ticker sub-second
    private boolean countdownRunning = false;
    private long countdownInitMs = 45_000L; // đọc từ prefs
    private long endAtMs = 0L;
    private static final int UI_INTERVAL_MS = 16; // ~60Hz

    // --- Auto reset khi về 0 ---
    private static final long AUTO_RESET_DELAY_MS = 3_000L; // 3 giây
    private boolean autoResetScheduled = false;
    private final Runnable autoResetRunnable = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            resetCountdown(true); // true = clear overlay
            autoResetScheduled = false;
        }
    };

    // SoundPool low-latency
    private SoundPool soundPool;
    private int soundId = 0;               // spike_no.wav (nổ)
    private boolean soundLoaded = false;
    private int warmupStreamId = 0;
    private boolean explosionPlayed = false;

    // Thông báo (spike_defuse.wav)
    private int soundIdDefuse = 0;
    private boolean soundLoadedDefuse = false;
    private long notificationMs = 0L;      // mốc thông báo (giây trong prefs → ms), 0 = tắt
    private boolean notifyPlayed = false;

    // YOLO
    private TFLiteYoloDetector detector;
    private ExecutorService analysisExecutor;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean stopping = false; // chặn analyzer & close an toàn
    private Bitmap reusableBitmap = null;

    // Debounce
    private int stableCount = 0;
    private long firstSeenTs = 0L; // mốc lần đầu thấy spike trong lượt hiện tại

    // Zoom
    private CameraControl cameraControl;
    private Camera camera;
    private float currentZoomRatio = 1f;
    private float minZoom = 1f;
    private float maxZoom = 1f;
    private static final float ZOOM_EPS = 0.02f; // Threshold to avoid spamming

    // ===== Ticker: cập nhật UI mỗi ~16ms =====
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            long msLeft = endAtMs - SystemClock.elapsedRealtime();
            if (msLeft <= 0) {
                // phát tiếng nổ đúng lúc về 0 (một lần)
                if (!explosionPlayed && soundLoaded && soundPool != null) {
                    try { soundPool.play(soundId, 1f, 1f, 1, 0, 1f); } catch (Exception ignored) {}
                    explosionPlayed = true;
                }
                tvSeconds.setText(String.format(Locale.getDefault(), "0.000 s"));
                countdownRunning = false;
                stableCount = 0;
                tvSeconds.removeCallbacks(this);

                // --- Lên lịch auto-reset sau 3 giây ---
                if (!autoResetScheduled) {
                    autoResetScheduled = true;
                    tvSeconds.postDelayed(autoResetRunnable, AUTO_RESET_DELAY_MS);
                }
            } else {
                // Phát thông báo khi vượt qua mốc notificationMs (nếu bật)
                if (notificationMs > 0 && !notifyPlayed && msLeft <= notificationMs && soundLoadedDefuse && soundPool != null) {
                    try { soundPool.play(soundIdDefuse, 1f, 1f, 1, 0, 1f); } catch (Exception ignored) {}
                    notifyPlayed = true;
                }
                tvSeconds.setText(String.format(Locale.getDefault(), "%.3f s", msLeft / 1000.0));
                tvSeconds.removeCallbacks(this);
                tvSeconds.postDelayed(this, UI_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_preview);

        // Thay đổi màu status bar thành #058bd4
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.parseColor("#058bd4"));
        }

        // ==== Bind views (đúng ID trong layout bạn gửi) ====
        previewView = findViewById(R.id.preview_view);
        overlay     = findViewById(R.id.overlay);
        tvCountdownLabel = findViewById(R.id.tv_countdown_label);
        tvSeconds        = findViewById(R.id.tv_countdown);
        btnBack          = findViewById(R.id.btn_back);
        btn_reset        = findViewById(R.id.btn_reset);

        // PreviewView tối ưu & FIT_CENTER để mapping overlay đúng
        previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);

        // Pinch‑to‑zoom
        ScaleGestureDetector scaleGestureDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (cameraControl == null) return false;
                        float candidate = currentZoomRatio * detector.getScaleFactor();
                        candidate = Math.max(minZoom, Math.min(maxZoom, candidate));
                        if (Math.abs(candidate - currentZoomRatio) > ZOOM_EPS) {
                            currentZoomRatio = candidate;
                            cameraControl.setZoomRatio(candidate);
                        }
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        if (camera != null && camera.getCameraInfo().getZoomState().getValue() != null) {
                            Float zr = camera.getCameraInfo().getZoomState().getValue().getZoomRatio();
                            if (zr != null) currentZoomRatio = zr;
                        }
                    }
                });
        previewView.setOnTouchListener((v, event) -> { scaleGestureDetector.onTouchEvent(event); return true; });

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // ==== Countdown & Notification từ prefs (giữ thập phân) ====
        tvCountdownLabel.setText("Đếm ngược:");
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        float countdownSec = prefs.getFloat(KEY_COUNTDOWN, 45f);
        countdownInitMs = Math.max(0L, (long) Math.round(countdownSec * 1000f));
        tvSeconds.setText(String.format(Locale.getDefault(), "%.3f s", countdownInitMs / 1000.0));
        // mốc thông báo (<= countdownInitMs). 0 hoặc >= countdownInitMs → tắt
        float notificationSec = prefs.getFloat(KEY_NOTIFICATION, 0f);
        long notif = (long) Math.round(Math.max(0f, notificationSec) * 1000f);
        notificationMs = (notif > 0 && notif < countdownInitMs) ? notif : 0L;

        // ==== Detector ====
        detector = new TFLiteYoloDetector(this,
                "best_float16_no_nms_448.tflite",
                448,
                CONF_TH);
        try {
            detector.initialize(/*useGpu*/ false, /*threads*/ 4, /*forceGpu*/ false, /*useNnapi*/ false); // tắt NNAPI để tránh crash driver
        } catch (Exception e) { e.printStackTrace(); }

        analysisExecutor = Executors.newSingleThreadExecutor();

        // ==== SoundPool low-latency + warm-up (res/raw/spike_no.wav & spike_defuse.wav) ====
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setAudioAttributes(attrs)
                    .setMaxStreams(2)
                    .build();
            soundId = soundPool.load(this, R.raw.spike_no, 1);
            soundIdDefuse = soundPool.load(this, R.raw.spike_defuse, 1);
            soundPool.setOnLoadCompleteListener((sp, id, status) -> {
                if (status == 0) {
                    if (id == soundId) {
                        soundLoaded = true;
                        // warm-up: play silent rồi stop ~60ms để mở đường audio
                        warmupStreamId = sp.play(soundId, 0f, 0f, 1, 0, 1f);
                        tvSeconds.postDelayed(() -> {
                            try { if (warmupStreamId != 0) { sp.stop(warmupStreamId); warmupStreamId = 0; } } catch (Exception ignored) {}
                        }, 60);
                    } else if (id == soundIdDefuse) {
                        soundLoadedDefuse = true;
                        // warm-up nhẹ cho defuse (im lặng)
                        int sid = sp.play(soundIdDefuse, 0f, 0f, 1, 0, 1f);
                        tvSeconds.postDelayed(() -> { try { sp.stop(sid); } catch (Exception ignored) {} }, 60);
                    }
                }
            });
        } catch (Exception e) { e.printStackTrace(); }

        // ==== Reset button: trở về thời gian gốc + reset trạng thái ====
        if (btn_reset != null) {
            btn_reset.setOnClickListener(v -> resetCountdown(true));
        }

        // ==== Permission ====
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        } else {
            startCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopping = true;
        // 1) Ngắt camera để analyzer không nhận thêm frame
        try { ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get(); provider.unbindAll(); } catch (Exception ignored) {}
        // 2) Tắt executor và chờ thoát gọn
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
            try { analysisExecutor.awaitTermination(1, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        // 3) Đợi nếu còn đang infer dở
        int spin = 0; while (busy.get() && spin++ < 50) { try { Thread.sleep(10); } catch (InterruptedException ignored) {} }
        // 4) Đóng detector & audio
        try { detector.close(); } catch (Exception ignored) {}
        stopCountdown();
        try { if (soundPool != null) { soundPool.release(); soundPool = null; } } catch (Exception ignored) {}

        // Hủy mọi auto-reset đang chờ
        tvSeconds.removeCallbacks(autoResetRunnable);
        autoResetScheduled = false;
    }

    // ===== Countdown helpers =====
    public void startCountdown() {
        if (countdownRunning) return;

        // Khi bắt đầu lượt mới, hủy mọi auto-reset đã lên lịch trước đó
        tvSeconds.removeCallbacks(autoResetRunnable);
        autoResetScheduled = false;

        countdownRunning = true;
        explosionPlayed = false; // reset cho lượt mới
        long now = SystemClock.elapsedRealtime();
        long detectionDelayMs = (firstSeenTs > 0L) ? (now - firstSeenTs) : 0L;
        if (detectionDelayMs < 0L) detectionDelayMs = 0L;
        long startMs = Math.max(0L, countdownInitMs - detectionDelayMs);
        endAtMs = now + startMs;
        // Nếu ngay lúc bắt đầu đã ở dưới mốc thông báo, coi như đã qua mốc => không phát notify
        notifyPlayed = (notificationMs > 0 && startMs <= notificationMs);
        tvSeconds.removeCallbacks(ticker);
        tvSeconds.setText(String.format(Locale.getDefault(), "%.3f s", startMs / 1000.0));
        tvSeconds.post(ticker);
    }

    private void stopCountdown() {
        countdownRunning = false;
        tvSeconds.removeCallbacks(ticker);
    }

    private void resetCountdown(boolean alsoClearOverlay) {
        // Dừng ticker + hủy auto-reset chờ
        stopCountdown();
        tvSeconds.removeCallbacks(autoResetRunnable);
        autoResetScheduled = false;

        // Đưa về trạng thái ban đầu
        stableCount = 0;
        firstSeenTs = 0L;
        explosionPlayed = false;
        notifyPlayed = false;
        tvSeconds.setText(String.format(Locale.getDefault(), "%.3f s", countdownInitMs / 1000.0));

        if (alsoClearOverlay && overlay != null) {
            overlay.setDetections(new ArrayList<>());
        }
    }

    // ===== CameraX =====
    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();
                cameraProvider.unbindAll();

                Preview preview = new Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                        .setTargetRotation(previewView.getDisplay().getRotation())
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                        .setTargetRotation(previewView.getDisplay().getRotation())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setOutputImageRotationEnabled(true) // RGBA trả theo orientation của UI
                        .build();

                analysis.setAnalyzer(analysisExecutor, this::analyze);

                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                cameraControl = camera.getCameraControl();

                camera.getCameraInfo().getZoomState().observe(this, state -> {
                    if (state == null) return;
                    minZoom = state.getMinZoomRatio();
                    maxZoom = state.getMaxZoomRatio();
                    currentZoomRatio = state.getZoomRatio();
                });
            } catch (Exception e) { e.printStackTrace(); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(@NonNull ImageProxy image) {
        if (stopping) { image.close(); return; }
        try {
            if (!busy.compareAndSet(false, true)) { image.close(); return; }

            // RGBA_8888 → Bitmap (tái sử dụng)
            reusableBitmap = ImageProxyExt.toBitmapRGBA(image, reusableBitmap);
            Bitmap frame = reusableBitmap;

            // YOLO detect
            List<TFLiteYoloDetector.Detection> dets = detector.detect(frame, IOU_TH, TOP_K);

            // Map box từ kích thước frame -> overlay (FIT_CENTER)
            final List<TFLiteYoloDetector.Detection> mapped = mapForOverlay(
                    dets,
                    frame.getWidth(), frame.getHeight(),
                    overlay != null ? overlay.getWidth() : previewView.getWidth(),
                    overlay != null ? overlay.getHeight() : previewView.getHeight()
            );

            // Cập nhật UI: vẽ khung + debounce + countdown
            runOnUiThread(() -> {
                if (overlay != null) overlay.setDetections(mapped);

                boolean hasSpike = false;
                for (TFLiteYoloDetector.Detection d : mapped) {
                    if (d.getScore() >= CONF_TH) { hasSpike = true; break; }
                }
                if (hasSpike) {
                    if (stableCount == 0) { firstSeenTs = SystemClock.elapsedRealtime(); }
                    stableCount++;
                    if (!countdownRunning && stableCount >= MIN_STABLE_FRAMES) startCountdown();
                } else {
                    stableCount = Math.max(0, stableCount - 1);
                    if (stableCount == 0) firstSeenTs = 0L;
                }
            });
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            image.close();
            busy.set(false);
        }
    }

    // Scale + letterbox từ frame -> overlay (FIT_CENTER)
    private List<TFLiteYoloDetector.Detection> mapForOverlay(List<TFLiteYoloDetector.Detection> list,
                                                             int frameW, int frameH,
                                                             int viewW, int viewH) {
        if (viewW == 0 || viewH == 0) return list; // view chưa layout xong
        float scale = Math.min(viewW / (float) frameW, viewH / (float) frameH);
        float padX = (viewW - frameW * scale) / 2f;
        float padY = (viewH - frameH * scale) / 2f;

        List<TFLiteYoloDetector.Detection> out = new ArrayList<>(list.size());
        for (TFLiteYoloDetector.Detection d : list) {
            RectF r = d.getBox();
            RectF m = new RectF(
                    r.left * scale + padX,
                    r.top * scale + padY,
                    r.right * scale + padX,
                    r.bottom * scale + padY
            );
            out.add(new TFLiteYoloDetector.Detection(m, d.getScore(), d.getLabel(), d.getClassId()));
        }
        return out;
    }
}
