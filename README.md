
# 📱 Gỡ Spike – AI Camera App

> 🔥 Một ứng dụng Android sử dụng **CameraX + TensorFlow Lite (YOLOv8n)** để nhận diện “Spike” trong game Valorant và đếm ngược đến lúc phát nổ.
> 👨‍💻 Project cá nhân của mình, tập trung vào xử lý ảnh thời gian thực, tối ưu latency và trải nghiệm người dùng.

---

## ✨ Tính năng chính

* 🎥 **Nhận diện Spike trong camera** bằng model YOLOv8n (TFLite).
* ⏱️ **Đếm ngược sub-second** (hiển thị 3 số thập phân).
* 🔍 **Zoom camera mượt mà** với pinch-to-zoom.
* 🎶 **Âm thanh cảnh báo & nổ Spike** bằng SoundPool low-latency.
* ⚙️ **Cài đặt linh hoạt**: chỉnh thời gian đếm ngược và mốc thông báo.
* 💾 **Lưu cấu hình** bằng SharedPreferences.

---

## 🛠️ Công nghệ sử dụng

* **Android (Java/Kotlin)**: Activity, CameraX, Material Design.
* **Machine Learning**: YOLOv8n → TFLite (`best_float16_no_nms_448.tflite`).
* **Camera pipeline**: CameraX `ImageAnalysis` + OverlayView custom.
* **Xử lý âm thanh**: SoundPool (PCM 16-bit WAV).
* **Lưu trữ nhẹ**: SharedPreferences.

---

## 📂 Cấu trúc project

```
app/
 ├── java/com/example/gospike/
 │    ├── MainActivity.java           # Màn hình chính
 │    ├── CameraPreviewActivity.java  # Camera + detect + countdown
 │    ├── SettingsActivity.java       # Cài đặt countdown & notification
 │    ├── TFLiteYoloDetector.kt       # Load + infer YOLOv8n (CPU/XNNPACK)
 │    └── ImageProxyExt.kt            # Chuyển ImageProxy → Bitmap RGBA
 ├── res/layout/                      # Layout XML (main, camera, settings)
 ├── res/raw/                         # Âm thanh spike_no.wav, spike_defuse.wav
 └── assets/                          # Model best_float16_no_nms_448.tflite
```

---

## 🚀 Cách chạy

1. Clone repo:

   ```bash
   git clone https://github.com/<your-username>/gospike.git
   cd gospike
   ```
2. Mở trong **Android Studio** (>= Arctic Fox).
3. Cắm thiết bị Android (khuyến nghị Xiaomi 11T hoặc máy có CPU mạnh).
4. Bấm **Run ▶** để build & cài đặt.

---

## 📸 Luồng hoạt động

1. **Mở app** → Màn hình chính (`MainActivity`).
2. **Bấm “Bắt đầu”** → Màn hình camera (`CameraPreviewActivity`).

   * CameraX preview.
   * Nhận diện Spike bằng YOLOv8n.
   * Đếm ngược chính xác đến thời điểm nổ.
   * Phát âm thanh ở mốc thông báo & khi nổ.
3. **Bấm “Cài đặt”** → Màn hình settings (`SettingsActivity`).

   * Nhập thời gian countdown.
   * Nhập mốc notification.
   * Lưu lại bằng SharedPreferences.

---

## 📊 Hiệu năng (thiết bị test: Xiaomi 11T)

* Model YOLOv8n (448×448, FP16, không NMS).
* Inference CPU/XNNPACK: \~215 ms / frame.
* GPU delegate không khả dụng; NNAPI tắt để tránh crash.
* Độ trễ cảm nhận giữa game và app: \~0.4s (có bù trễ động).

---

## 🔮 Hướng phát triển tiếp theo

* ⏳ Scheduler tuyệt đối cho audio (đồng bộ tốt hơn).
* 🛡️ Guard window quanh T₀ để tránh nghẽn.
* 📉 Giảm độ phân giải phân tích xuống 1280×720 để giảm latency.
* ⚡ Xem xét bật lại NNAPI nếu ổn định.

---

## 👨‍🎓 Về project

Đây là project mình thực hiện với tư cách **AI Intern** nhằm:

* Hiểu quy trình **triển khai model ML lên Android**.
* Làm quen với **CameraX, TFLite, SoundPool** và xử lý realtime.
* Nâng cao kỹ năng **tối ưu hiệu năng & latency** trong ứng dụng AI.

---

👉 Bạn có muốn mình viết luôn cho bạn **file README.md hoàn chỉnh** (Markdown chuẩn GitHub, có badges + ảnh minh họa chỗ để screenshot/video demo) để bạn chỉ việc copy vào repo không?
