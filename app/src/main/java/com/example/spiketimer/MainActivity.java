package com.example.spiketimer;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ConfigurationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.spiketimer.yolo.TFLiteYoloDetector;

import java.io.InputStream;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TFLiteYoloDetector detector = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Thay đổi màu status bar thành #058bd4
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.parseColor("#058bd4"));
        }
        // Log memory usage
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        ActivityManager tam = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        tam.getMemoryInfo(memInfo);
        Log.i("Memory", "Available: " + memInfo.availMem / 1024 / 1024 + " MB");

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Button btnStart = findViewById(R.id.btnStart);
        btnStart.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, CameraPreviewActivity.class))
        );

        Button btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, SettingsActivity.class))
        );

        // ---- Log thông tin OpenGL ES (để chẩn đoán GPU delegate) ----
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                ConfigurationInfo info = am.getDeviceConfigurationInfo();
                Log.i("GL-INFO", "reqGlEsVersion=0x" + Integer.toHexString(info.reqGlEsVersion));
            }
        } catch (Throwable t) {
            Log.w("GL-INFO", "Cannot read GL ES version: " + t.getMessage());
        }

        // ---- Khởi tạo detector (ưu tiên GPU, fallback NNAPI, cuối cùng CPU) ----
        try {
            detector = new TFLiteYoloDetector(
                    MainActivity.this,
                    "best_float16_no_nms_448.tflite",
                    448,
                    0.74f
            );
            detector.initialize(/*useGpu*/ true, /*numThreads*/ 4, /*forceGpu*/ true, /*useNnapi*/ true);
            Log.i("MainActivity", "Accel -> GPU: " + detector.isUsingGpu() + ", NNAPI: " + detector.isUsingNnapi());
        } catch (Exception initErr) {
            Log.e("MainActivity", "Detector init failed: " + initErr.getMessage(), initErr);
        }

        // ======= TEST VÒNG 3: detect ảnh tĩnh + NMS + vẽ kết quả (chạy trên thread phụ) =======
        new Thread(() -> {
            try {
                // 0) Kiểm tra assets bắt buộc
                try { getAssets().open("best_float16_no_nms_448.tflite").close();
                    Log.i("YoloTest", "Model file found in assets."); } catch (Exception e) {
                    Log.e("YoloTest", "Model missing at assets/best_float16_no_nms_448.tflite", e); return; }
                try { getAssets().open("val_test.jpg").close();
                    Log.i("YoloTest", "val_test.jpg found in assets."); } catch (Exception e) {
                    Log.e("YoloTest", "val_test.jpg missing at assets/val_test.jpg", e); return; }
                if (detector == null) { Log.e("YoloTest", "Detector is null. Init may have failed."); return; }

                // 1) Load ảnh test
                Bitmap bitmap;
                try (InputStream is = getAssets().open("val_test.jpg")) {
                    bitmap = BitmapFactory.decodeStream(is);
                }

                // 2) Chạy detect + benchmark sơ bộ
                long t0 = SystemClock.elapsedRealtime();
                List<TFLiteYoloDetector.Detection> results = detector.detect(bitmap, /*iou*/0.45f, /*topK*/50);
                long dt = SystemClock.elapsedRealtime() - t0;
                Log.i("YoloV3", "Detect count=" + results.size() + ", time=" + dt + " ms");
                for (int i = 0; i < Math.min(5, results.size()); i++) {
                    TFLiteYoloDetector.Detection d = results.get(i);
                    Log.i("YoloV3", "["+i+"] score=" + String.format("%.3f", d.getScore()) + " box=" + d.getBox());
                }

                // 3) Vẽ kết quả lên ảnh để quan sát trực quan
                Bitmap vis = drawDetections(bitmap, results);

                // 4) Hiển thị ảnh kết quả trên UI (dialog) để bạn eyeball
                runOnUiThread(() -> showImageDialog(vis, "Kết quả detect: " + results.size() + " box(s)"));

            } catch (Exception e) {
                Log.e("YoloTest", "Error during V3 test: " + e.getMessage(), e);
            }
        }).start();
        // ======= HẾT TEST VÒNG 3 =======
    }

    // --- Utils vẽ kết quả ---
    private Bitmap drawDetections(Bitmap src, List<TFLiteYoloDetector.Detection> results) {
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(out);

        float stroke = dp(2f);
        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(stroke);
        boxPaint.setColor(Color.GREEN);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(12f));

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0x88000000); // đen mờ cho nền chữ

        for (TFLiteYoloDetector.Detection d : results) {
            RectF r = d.getBox();
            canvas.drawRect(r, boxPaint);
            String label = String.format("spike %.2f", d.getScore());
            float tw = textPaint.measureText(label);
            float th = textPaint.getTextSize() + dp(6f);
            float x = Math.max(0, r.left);
            float y = Math.max(th, r.top);
            canvas.drawRect(x, y - th, x + tw + dp(8f), y, bgPaint);
            canvas.drawText(label, x + dp(4f), y - dp(4f), textPaint);
        }
        return out;
    }

    private void showImageDialog(Bitmap bmp, String title) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setImageBitmap(bmp);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(iv)
                .setPositiveButton("OK", (d, w) -> d.dismiss())
                .show();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (detector != null) detector.close();
        } catch (Exception ignored) {}
        detector = null;
    }
}