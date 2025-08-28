package com.example.spiketimer.yolo

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

private fun iou(a: RectF, b: RectF): Float {
    val interLeft = max(a.left, b.left)
    val interTop = max(a.top, b.top)
    val interRight = min(a.right, b.right)
    val interBottom = min(a.bottom, b.bottom)
    val interW = max(0f, interRight - interLeft)
    val interH = max(0f, interBottom - interTop)
    val inter = interW * interH
    val union = a.width() * a.height() + b.width() * b.height() - inter
    return if (union <= 0f) 0f else inter / union
}

fun nms(
    detections: List<Detection>,
    iouThresh: Float = 0.45f,
    topK: Int = 50
): List<Detection> {
    if (detections.isEmpty()) return emptyList()
    val sorted = detections.sortedByDescending { it.score }.toMutableList()
    val keep = ArrayList<Detection>(min(topK, sorted.size))

    while (sorted.isNotEmpty() && keep.size < topK) {
        val best = sorted.removeAt(0)
        keep.add(best)
        val it = sorted.iterator()
        while (it.hasNext()) {
            val d = it.next()
            if (iou(best.box, d.box) > iouThresh) it.remove()
        }
    }
    return keep
}
