package com.example.spiketimer.yolo

import android.graphics.RectF

data class Detection(
    val box: RectF,      // toạ độ trên ảnh gốc (left, top, right, bottom)
    val score: Float,    // confidence
    val label: String = "spike",
    val classId: Int = 0
)
