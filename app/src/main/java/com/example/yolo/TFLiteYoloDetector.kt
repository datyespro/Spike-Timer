package com.example.spiketimer.yolo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TFLiteYoloDetector(
    private val context: Context,
    private val modelPath: String = "best_float16_no_nms_448.tflite",
    private val inputSize: Int = 448,
    private val confThreshold: Float = 0.74f,
) {

    private var interpreter: Interpreter? = null
    private var accelDelegate: Delegate? = null  // có thể là GPU hoặc NNAPI (dùng chung biến)
    private var inputTensorType: org.tensorflow.lite.DataType? = null
    private var inputShape: IntArray? = null

    // Trạng thái tăng tốc
    private var usingGpu: Boolean = false
    private var usingNnapi: Boolean = false

    fun isUsingGpu(): Boolean = usingGpu
    fun isUsingNnapi(): Boolean = usingNnapi

    /**
     * Initialize bản cơ bản (giữ tương thích với code cũ).
     * - useGpu: thử bật GPU (reflection); nếu không được → CPU.
     * - numThreads: số luồng CPU khi chạy XNNPACK.
     */
    fun initialize(useGpu: Boolean = true, numThreads: Int = 4) {
        initialize(useGpu, numThreads, forceGpu = false, useNnapi = false)
    }

    /**
     * Initialize bản mở rộng:
     * - useGpu: có thử GPU không
     * - numThreads: số luồng CPU
     * - forceGpu: bỏ qua CompatibilityList khi thử GPU (ép tạo delegate nếu class có mặt)
     * - useNnapi: nếu GPU không bật được, thử NNAPI (nhiều máy Dimensity chạy ổn)
     */
    fun initialize(useGpu: Boolean, numThreads: Int, forceGpu: Boolean, useNnapi: Boolean) {
        // Reset trạng thái
        close()

        val opts = Interpreter.Options().apply {
            // XNNPACK cho CPU; an toàn khi kết hợp delegate
            setUseXNNPACK(true)
            setNumThreads(numThreads)
        }
        usingGpu = false
        usingNnapi = false

        // 1) Thử GPU trước (nếu được bật)
        if (useGpu) {
            val gpu = maybeCreateGpuDelegateReflective(force = forceGpu)
            if (gpu != null) {
                try {
                    opts.addDelegate(gpu)
                    accelDelegate = gpu
                    usingGpu = true
                    Log.i(TAG, "GPU delegate ENABLED.")
                } catch (t: Throwable) {
                    Log.w(TAG, "Add GPU delegate failed, fallback: ${t.message}")
                    runCatching { closeDelegateReflective(gpu) }
                }
            } else {
                Log.w(TAG, "GPU delegate not available (force=$forceGpu).")
            }
        }

        // 2) Nếu chưa có GPU và cho phép NNAPI → thử NNAPI
        if (!usingGpu && useNnapi) {
            val nn = maybeCreateNnApiDelegateReflective()
            if (nn != null) {
                try {
                    opts.addDelegate(nn)
                    accelDelegate = nn
                    usingNnapi = true
                    Log.i(TAG, "NNAPI delegate ENABLED.")
                } catch (t: Throwable) {
                    Log.w(TAG, "Add NNAPI delegate failed, fallback: ${t.message}")
                    runCatching { closeDelegateReflective(nn) }
                }
            } else {
                Log.w(TAG, "NNAPI delegate not available.")
            }
        }

        // 3) Tạo Interpreter
        val buffer = loadModelFile(modelPath)
        interpreter = Interpreter(buffer, opts)

        // 4) Log input/output info
        interpreter?.let { itp ->
            val inTensor = itp.getInputTensor(0)
            inputTensorType = inTensor.dataType()
            inputShape = inTensor.shape()
            Log.i(TAG, "Input tensor: dtype=$inputTensorType, shape=${inputShape?.contentToString()}")

            val outCount = itp.outputTensorCount
            Log.i(TAG, "Output tensor count = $outCount")
            for (i in 0 until outCount) {
                val t = itp.getOutputTensor(i)
                Log.i(TAG, "Output[$i]: dtype=${t.dataType()}, shape=${t.shape().contentToString()}")
            }
        }

        // 5) Warmup để lần sau mượt hơn (bỏ qua lỗi yên lặng)
        runCatching {
            val dummy = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            run(dummy)
        }
    }

    fun close() {
        runCatching { interpreter?.close() }
        interpreter = null
        runCatching { accelDelegate?.let { closeDelegateReflective(it) } }
        accelDelegate = null
        usingGpu = false
        usingNnapi = false
    }

    /**
     * Chạy infer (giữ nguyên hành vi cũ):
     * Trả về map<outIndex, FloatArray> (flat) để debug/log.
     */
    fun run(bitmap: Bitmap): Map<Int, FloatArray> {
        val itp = interpreter ?: error("Call initialize() first")

        // 1) Letterbox về inputSize x inputSize
        val letterboxed = letterbox(bitmap, inputSize, inputSize)

        // 2) Chuẩn bị input theo dtype thực tế
        val inType = inputTensorType ?: org.tensorflow.lite.DataType.FLOAT32
        val inputBuffer: Any = if (inType == org.tensorflow.lite.DataType.FLOAT32) {
            toFloatBuffer(letterboxed)
        } else {
            toByteBufferUINT8(letterboxed)
        }

        // 3) Cấp phát container output THEO ĐÚNG SHAPE (mảng lồng nhau)
        val outCount = itp.outputTensorCount
        val outputs = HashMap<Int, Any>(outCount)
        val flatOutputs = HashMap<Int, FloatArray>(outCount)

        for (i in 0 until outCount) {
            val t = itp.getOutputTensor(i)
            val shape = t.shape() // ví dụ [1, 5, 4116]
            val container = makeOutputContainer(shape, t.dataType())
            outputs[i] = container
        }

        // 4) Run
        itp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        // 5) Flatten để log/tiện xử lý vòng sau
        for (i in 0 until outCount) {
            val raw = outputs[i]!!
            val flat = flattenToFloatArray(raw)
            flatOutputs[i] = flat
            Log.i(TAG, "Out[$i] size=${flat.size}, head=${flat.take(8)}")
        }

        return flatOutputs
    }

    // -------------------- VÒNG 3: Decode + NMS + API Detection --------------------

    /** Kết quả cuối cùng trả về cho app */
    data class Detection(
        val box: RectF,      // toạ độ trên ảnh gốc (left, top, right, bottom)
        val score: Float,
        val label: String = "spike",
        val classId: Int = 0
    )

    // Struct giữ thông tin để map ngược từ không gian input(448) về ảnh gốc
    private data class LetterboxInfo(
        val ratio: Float,
        val padX: Float,
        val padY: Float,
        val srcW: Int,
        val srcH: Int,
        val dstW: Int,
        val dstH: Int
    )

    // API mới: detect trên ảnh tĩnh, trả List<Detection> đã qua NMS
    fun detect(
        srcBitmap: Bitmap,
        iouThresh: Float = 0.45f,
        topK: Int = 50
    ): List<Detection> {
        val itp = interpreter ?: error("Call initialize() first")

        // 1) Letterbox + info để map ngược
        val (letter, info) = letterboxWithInfo(srcBitmap, inputSize, inputSize)

        // 2) Chuẩn bị input buffer
        val inType = inputTensorType ?: org.tensorflow.lite.DataType.FLOAT32
        val inputBuffer: Any = if (inType == org.tensorflow.lite.DataType.FLOAT32) {
            toFloatBuffer(letter)
        } else {
            toByteBufferUINT8(letter)
        }

        // 3) CHUẨN: tạo container theo đúng shape [1,5,4116]
        val outTensor = itp.getOutputTensor(0)
        val outShape = outTensor.shape() // [1, 5, 4116]
        val outContainer: Any = makeOutputContainer(outShape, outTensor.dataType())

        // 4) Run với Map<Int, Any> (không dùng hashMapOf(0 to ...) vì sẽ suy luận sai generic)
        val outMap: MutableMap<Int, Any> = HashMap()
        outMap[0] = outContainer
        itp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outMap)

        // 5) Flatten ra FloatArray để decode
        val outArr = flattenToFloatArray(outContainer)

        // 6) Decode ở không gian input (448)
        val (inputBoxes, inputScores) = decodeYoloOutputs(outArr, inputSize, inputSize, confThreshold)

        // 7) Map về ảnh gốc + NMS
        val mapped = ArrayList<Detection>(inputBoxes.size)
        for (i in inputBoxes.indices) {
            val rect = mapToOriginal(inputBoxes[i], info)
            mapped.add(Detection(rect, inputScores[i]))
        }
        return nms(mapped, iouThresh, topK)
    }


    /**
     * Decode tensor [5 x N] → (boxes in input-space, scores)
     * 5 kênh = [cx, cy, w, h, conf].
     * Tự dò xem toạ độ có chuẩn hoá 0..1 hay đã là pixel (≤1.2 ⇒ coi là chuẩn hoá).
     */
    private fun decodeYoloOutputs(
        out: FloatArray,
        inputW: Int,
        inputH: Int,
        confTh: Float,
    ): Pair<List<RectF>, List<Float>> {
        val C = 5
        val N = out.size / C
        if (N <= 0) return emptyList<RectF>() to emptyList()

        // Đoán normalized?
        var isNormalized = false
        run {
            var maxVal = 0f
            var checked = 0
            val start = 0
            val end = min(N, 200)
            for (i in start until end) {
                maxVal = max(maxVal, out[i])
                maxVal = max(maxVal, out[N + i])
                maxVal = max(maxVal, out[2 * N + i])
                maxVal = max(maxVal, out[3 * N + i])
                checked++
            }
            isNormalized = maxVal <= 1.2f
        }

        val boxes = ArrayList<RectF>(64)
        val scores = ArrayList<Float>(64)

        for (i in 0 until N) {
            val conf = out[4 * N + i]
            if (conf < confTh) continue

            var cx = out[0 * N + i]
            var cy = out[1 * N + i]
            var w  = out[2 * N + i]
            var h  = out[3 * N + i]

            if (isNormalized) {
                cx *= inputW
                cy *= inputH
                w  *= inputW
                h  *= inputH
            }

            val left = (cx - w / 2f).coerceIn(0f, inputW.toFloat())
            val top  = (cy - h / 2f).coerceIn(0f, inputH.toFloat())
            val right = (cx + w / 2f).coerceIn(0f, inputW.toFloat())
            val bottom = (cy + h / 2f).coerceIn(0f, inputH.toFloat())

            if (right > left && bottom > top) {
                boxes.add(RectF(left, top, right, bottom))
                scores.add(conf)
            }
        }
        return boxes to scores
    }

    /** Map box từ input(448) -> ảnh gốc (bỏ padding) */
    private fun mapToOriginal(b: RectF, info: LetterboxInfo): RectF {
        val x0 = (b.left   - info.padX) / info.ratio
        val y0 = (b.top    - info.padY) / info.ratio
        val x1 = (b.right  - info.padX) / info.ratio
        val y1 = (b.bottom - info.padY) / info.ratio

        val l = x0.coerceIn(0f, (info.srcW - 1).toFloat())
        val t = y0.coerceIn(0f, (info.srcH - 1).toFloat())
        val r = x1.coerceIn(0f, (info.srcW - 1).toFloat())
        val btm = y1.coerceIn(0f, (info.srcH - 1).toFloat())
        return RectF(l, t, r, btm)
    }

    // ---------------- GPU/NNAPI via Reflection ----------------

    /**
     * Tạo GPU delegate bằng reflection để tránh lỗi compile nếu thiếu class.
     * - force=true: bỏ qua CompatibilityList (vẫn cần class tồn tại).
     */
    private fun maybeCreateGpuDelegateReflective(force: Boolean = false): Delegate? {
        // 1) Kiểm tra class có trong classpath chưa (nếu fail: thiếu AAR GPU trong APK)
        val gpuCls = try {
            Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
        } catch (cnfe: Throwable) {
            Log.e(TAG, "GPU class not found. Is 'tensorflow-lite-gpu' on classpath & packaged?", cnfe)
            return null
        }

        // 2) Nếu không force, kiểm tra compatibility list
        if (!force) {
            try {
                val compatCls = Class.forName("org.tensorflow.lite.gpu.CompatibilityList")
                val compat = compatCls.getConstructor().newInstance()
                val supported = compatCls.getMethod("isDelegateSupportedOnThisDevice").invoke(compat) as? Boolean ?: false
                if (!supported) {
                    Log.w(TAG, "CompatibilityList says GPU NOT supported.")
                    return null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "CompatibilityList check failed, will continue: ${t.message}")
            }
        }

        // 3) Ưu tiên ctor với Options; nếu fail thì thử ctor mặc định
        try {
            val optionsCls = Class.forName("org.tensorflow.lite.gpu.GpuDelegate\$Options")
            val options = optionsCls.getConstructor().newInstance()
            runCatching {
                optionsCls.getMethod("setPrecisionLossAllowed", Boolean::class.javaPrimitiveType).invoke(options, true)
            }
            runCatching {
                // 1 ≈ SUSTAINED_SPEED (nếu method không tồn tại, ignore)
                optionsCls.getMethod("setInferencePreference", Int::class.javaPrimitiveType).invoke(options, 1)
            }
            val delegate = gpuCls.getConstructor(optionsCls).newInstance(options)
            Log.i(TAG, "Created GpuDelegate with Options()")
            return delegate as Delegate
        } catch (e1: Throwable) {
            Log.w(TAG, "GpuDelegate(options) failed: ${e1.javaClass.simpleName}: ${e1.message}", e1)
            // Fallback: thử ctor mặc định
            try {
                val delegate = gpuCls.getConstructor().newInstance()
                Log.i(TAG, "Created GpuDelegate with default ctor")
                return delegate as Delegate
            } catch (e2: Throwable) {
                Log.w(TAG, "GpuDelegate() default ctor failed: ${e2.javaClass.simpleName}: ${e2.message}", e2)
            }
        }

        Log.w(TAG, "GPU reflective creation failed after both ctors.")
        return null
    }


    /** Tạo NNAPI delegate bằng reflection (nếu khả dụng trên máy) */
    private fun maybeCreateNnApiDelegateReflective(): Delegate? {
        return try {
            val cls = Class.forName("org.tensorflow.lite.nnapi.NnApiDelegate")
            val ctor = cls.getConstructor()
            ctor.newInstance() as? Delegate
        } catch (t: Throwable) {
            Log.w(TAG, "NNAPI reflective creation failed: ${t.message}")
            null
        }
    }

    /** Đóng delegate bằng reflection để tránh import trực tiếp */
    private fun closeDelegateReflective(delegate: Delegate) {
        runCatching {
            val close = delegate::class.java.methods.firstOrNull { it.name == "close" && it.parameterCount == 0 }
            close?.invoke(delegate)
        }
    }

    // ---------------- Helpers ----------------

    private fun loadModelFile(assetName: String): ByteBuffer {
        context.assets.open(assetName).use { input ->
            val bytes = input.readBytes()
            val bb = ByteBuffer.allocateDirect(bytes.size)
            bb.order(ByteOrder.nativeOrder())
            bb.put(bytes)
            bb.rewind()
            return bb
        }
    }

    /** Letterbox (giữ tỉ lệ) về (dstW x dstH) */
    private fun letterbox(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val ratio = minOf(dstW.toFloat() / src.width, dstH.toFloat() / src.height)
        val newW = (src.width * ratio).roundToInt()
        val newH = (src.height * ratio).roundToInt()

        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val left = ((dstW - newW) / 2f)
        val top  = ((dstH - newH) / 2f)
        val rect = RectF(left, top, left + newW, top + newH)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(Bitmap.createScaledBitmap(src, newW, newH, true), null, rect, paint)
        return out
    }

    /** Letterbox trả thêm info để map ngược */
    private fun letterboxWithInfo(src: Bitmap, dstW: Int, dstH: Int): Pair<Bitmap, LetterboxInfo> {
        val ratio = minOf(dstW.toFloat() / src.width, dstH.toFloat() / src.height)
        val newW = (src.width * ratio).roundToInt()
        val newH = (src.height * ratio).roundToInt()
        val padX = (dstW - newW) / 2f
        val padY = (dstH - newH) / 2f

        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val rect = RectF(padX, padY, padX + newW, padY + newH)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(Bitmap.createScaledBitmap(src, newW, newH, true), null, rect, paint)

        val info = LetterboxInfo(ratio, padX, padY, src.width, src.height, dstW, dstH)
        return out to info
    }

    /** Chuẩn hoá [0..1], NHWC float32, RGB */
    private fun toFloatBuffer(bmp: Bitmap): ByteBuffer {
        val w = bmp.width
        val h = bmp.height
        val bb = ByteBuffer.allocateDirect(4 * w * h * 3)
        bb.order(ByteOrder.nativeOrder())

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[idx++]
                val r = (c shr 16 and 0xFF) / 255f
                val g = (c shr 8  and 0xFF) / 255f
                val b = (c        and 0xFF) / 255f
                bb.putFloat(r); bb.putFloat(g); bb.putFloat(b)
            }
        }
        bb.rewind()
        return bb
    }

    /** Trường hợp input là UINT8 (hiếm) */
    private fun toByteBufferUINT8(bmp: Bitmap): ByteBuffer {
        val w = bmp.width
        val h = bmp.height
        val bb = ByteBuffer.allocateDirect(w * h * 3)
        bb.order(ByteOrder.nativeOrder())

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[idx++]
                bb.put((c shr 16 and 0xFF).toByte())
                bb.put((c shr 8  and 0xFF).toByte())
                bb.put((c        and 0xFF).toByte())
            }
        }
        bb.rewind()
        return bb
    }

    /** Tạo container mảng lồng nhau theo shape để TFLite copyTo() hợp lệ */
    private fun makeOutputContainer(shape: IntArray, dtype: org.tensorflow.lite.DataType): Any {
        require(dtype == org.tensorflow.lite.DataType.FLOAT32) {
            "Only FLOAT32 outputs are supported in this helper. Got $dtype"
        }
        return when (shape.size) {
            1 -> FloatArray(shape[0])
            2 -> Array(shape[0]) { FloatArray(shape[1]) }
            3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
            4 -> Array(shape[0]) { Array(shape[1]) { Array(shape[2]) { FloatArray(shape[3]) } } }
            else -> error("Unsupported output rank: ${shape.size}, shape=${shape.contentToString()}")
        }
    }

    /** Flatten mọi cấp mảng lồng nhau về FloatArray để tiện thao tác/log */
    private fun flattenToFloatArray(any: Any): FloatArray {
        val out = ArrayList<Float>(2048)
        fun rec(x: Any?) {
            if (x == null) return
            when (x) {
                is FloatArray -> out.addAll(x.asList())
                is Array<*>   -> x.forEach { rec(it) }
                else          -> error("Unsupported element type in output container: ${x::class}")
            }
        }
        rec(any)
        return out.toFloatArray()
    }

    // ---------------- NMS helpers ----------------

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

    private fun nms(
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

    companion object {
        private const val TAG = "YoloDetector"
    }
}
