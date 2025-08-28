package com.example.spiketimer;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.os.Build;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {
    private EditText etCountdownTime;
    private EditText etNotificationTime;
    private static final String PREFS_NAME = "spike_prefs";
    private static final String KEY_COUNTDOWN = "countdown_time";      // seconds (float)
    private static final String KEY_NOTIFICATION = "notification_time"; // seconds (float)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Thay đổi màu status bar thành #058bd4
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.parseColor("#058bd4"));
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        etCountdownTime = findViewById(R.id.etCountdownTime);
        etNotificationTime = findViewById(R.id.etNotificationTime);

        // Hiển thị giá trị hiện tại với 3 chữ số thập phân
        float savedCountdown = prefs.getFloat(KEY_COUNTDOWN, 45f);
        float savedNotification = prefs.getFloat(KEY_NOTIFICATION, 0f);
        etCountdownTime.setText(String.format(Locale.getDefault(), "%.3f", savedCountdown));
        etNotificationTime.setText(String.format(Locale.getDefault(), "%.3f", savedNotification));

        Button btnClose = findViewById(R.id.btnClose);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                float countdownValue = parseLocaleFloat(etCountdownTime.getText());
                float notificationValue = parseLocaleFloat(etNotificationTime.getText());

                // Clamp đơn giản: không âm, và giới hạn tối đa 24h để tránh lỗi nhập
                countdownValue = clamp(countdownValue, 0f, 24f * 3600f);
                notificationValue = clamp(notificationValue, 0f, 24f * 3600f);

                prefs.edit()
                        .putFloat(KEY_COUNTDOWN, countdownValue)
                        .putFloat(KEY_NOTIFICATION, notificationValue)
                        .apply();

                finish();
            }
        });
    }

    private static float clamp(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /**
     * Parse số thập phân theo thói quen nhập của người dùng:
     * - Chấp nhận cả "," và "." làm dấu thập phân.
     * - Trả về 0 nếu rỗng/không hợp lệ.
     */
    private static float parseLocaleFloat(Editable s) {
        if (s == null) return 0f;
        String raw = s.toString().trim();
        if (TextUtils.isEmpty(raw)) return 0f;
        // Chuẩn hoá: thay dấu phẩy thành chấm để Float.parseFloat hiểu
        raw = raw.replace(',', '.');
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }
}
