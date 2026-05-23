package com.nothing.camera2magic.hook

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.utils.Dog
import java.io.FileNotFoundException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


object SourceManager {
    private const val TAG = "[MediaSource]"
    private const val INSTANT_FRAME_ASPECT_WIDTH = 4f
    private const val INSTANT_FRAME_ASPECT_HEIGHT = 3f
    private const val INSTAGRAM_QUICKSNAP_MARGIN_DIMEN_ID = 0x7f070000
    private const val INSTAGRAM_QUICKSNAP_MARGIN_DP = 16f
    private const val LOCAL_MEDIA_TYPE_VIDEO = 0x0000
    private const val LOCAL_MEDIA_TYPE_IMAGE = 0x0001
    private const val NETWORK_MEDIA_TYPE_RTSP = 0x0100
    private const val KEY_MODULE_ENABLED = "main_module_enabled"
    private const val KEY_PLAY_SOUND = "main_play_sound"
    private const val KEY_ENABLE_LOG = "main_enable_log"
    private const val KEY_SQUARE_IMAGE_FIT = "main_square_image_fit"
    private const val KEY_INSTANT_CROP_ZOOM = "main_instant_crop_zoom"
    private const val KEY_INSTANT_CROP_OFFSET_X = "main_instant_crop_offset_x"
    private const val KEY_INSTANT_CROP_OFFSET_Y = "main_instant_crop_offset_y"
    private const val KEY_MEDIA_SOURCE = "media_source" // 0: local, 1: network
    private const val KEY_LOCAL_MEDIA_TYPE = "local_media_type" // 0: video, 1: image
    private const val KEY_LOCAL_VIDEO_ID = "local_video_id"
    private const val KEY_LOCAL_IMAGE_ID = "local_image_id"
    private const val KEY_NETWORK_RTSP_URI = "network_rtsp_uri"

    private lateinit var prefs: SharedPreferences

    private var lastMediaFingerprint: String = ""
    @Volatile
    var moduleEnabled: Boolean = true
        private set
    @Volatile
    private var playSound: Boolean = false
    @Volatile
    var enableLog: Boolean = false
        private set
    @Volatile
    var squareImageFit: Boolean = false
        private set
    @Volatile
    private var instantCropZoom: Float = 1f
    @Volatile
    private var instantCropOffsetX: Float = 0f
    @Volatile
    private var instantCropOffsetY: Float = 0f
    @Volatile
    private var mediaSource: Int = 0
    @Volatile
    private var mediaType: Int = 0
    @Volatile
    private var selectedMedia: Int = 0x0000
    @Volatile
    var toastMessage: String? = null
    @Volatile
    private var videoId: Long = -1L
    @Volatile
    private var imageId: Long = -1L
    @Volatile
    private var rtspUri: String = ""

    @Volatile
    var mediaIsReady: Boolean = false
        private set


    fun init(remotePrefs: SharedPreferences) {
        this.prefs = remotePrefs
        refreshPrefs()
    }

    fun refreshAndDispatch(force: Boolean = false) {
        refreshPrefs()
        if (!moduleEnabled) {
            toastMessage = "模块未启用"
            return
        }
        val fingerprint = getMediaFingerprint()
        if (force || fingerprint != lastMediaFingerprint) {
            Dog.i(TAG, "dispatch force=$force fingerprint=$fingerprint", enableLog)
            dispatchMediaSourceToNative()
            lastMediaFingerprint = fingerprint
        }
    }

    private fun refreshPrefs() {
        try {
            if (!::prefs.isInitialized) return
            moduleEnabled = prefs.getBoolean(KEY_MODULE_ENABLED, true)
            playSound = prefs.getBoolean(KEY_PLAY_SOUND, false)
            enableLog = prefs.getBoolean(KEY_ENABLE_LOG, false)
            squareImageFit = prefs.getBoolean(KEY_SQUARE_IMAGE_FIT, false)
            instantCropZoom = prefs.getFloat(KEY_INSTANT_CROP_ZOOM, 1f).coerceIn(1f, 5f)
            instantCropOffsetX = prefs.getFloat(KEY_INSTANT_CROP_OFFSET_X, 0f).coerceIn(-1f, 1f)
            instantCropOffsetY = prefs.getFloat(KEY_INSTANT_CROP_OFFSET_Y, 0f).coerceIn(-1f, 1f)

            mediaSource = prefs.getInt(KEY_MEDIA_SOURCE, 0)
            mediaType = prefs.getInt(KEY_LOCAL_MEDIA_TYPE, 0)
            selectedMedia = (mediaSource shl 8) or mediaType

            videoId = prefs.getLong(KEY_LOCAL_VIDEO_ID, -1L)
            imageId = prefs.getLong(KEY_LOCAL_IMAGE_ID, -1L)
            rtspUri = prefs.getString(KEY_NETWORK_RTSP_URI, "") ?: ""

            NativeBridge.updateGlobalConfig(playSound, enableLog)
            Dog.i(
                TAG,
                "prefs enabled=$moduleEnabled media=$selectedMedia image=$imageId " +
                    "squareFit=$squareImageFit crop=$instantCropZoom,$instantCropOffsetX,$instantCropOffsetY log=$enableLog",
                enableLog
            )

        } catch (e: Exception) { /* Do Nothing */ }
    }

    fun isReadyForHook(): Boolean = moduleEnabled && mediaIsReady

    private fun dispatchMediaSourceToNative() {
        mediaIsReady = false
        when (selectedMedia) {
            LOCAL_MEDIA_TYPE_VIDEO -> { updateVideoSource() }
            LOCAL_MEDIA_TYPE_IMAGE -> { updateImageSource() }
            NETWORK_MEDIA_TYPE_RTSP -> { }
        }
    }

    private fun getMediaFingerprint(): String {
        return when (selectedMedia) {
            0x0000 -> "$selectedMedia:$videoId"
            0x0001 -> "$selectedMedia:$imageId:$squareImageFit:$instantCropZoom:$instantCropOffsetX:$instantCropOffsetY"
            0x0100 -> "$selectedMedia:$rtspUri"
            else -> ""
        }
    }

    private fun updateVideoSource() {
        if (videoId == -1L) {
            NativeBridge.resetMediaSource()
            updateState(false, "未设置视频")
            return
        }
        val contentResolver = GlobalState.appContext.contentResolver
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)

        val result = runCatching {
            val afd = contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("无法打开视频：$uri")
            afd.use { it ->
                NativeBridge.processVideo(
                    it.parcelFileDescriptor.fd,
                    it.startOffset,
                    it.length
                )
            }
        }

        result.onSuccess { success ->
            val msg = if (success) "视频已就绪" else "视频接收异常(Native)"
            updateState(success, msg)
        }.onFailure { e ->
            val msg = when(e) {
                is SecurityException -> "无权限读取视频"
                else -> "图片传输异常(Java IO)"
            }
            updateState(false, msg)
        }
    }

    private fun updateImageSource() {
        if (imageId == -1L) {
            NativeBridge.resetMediaSource()
            updateState(false, "未设置图片")
            return
        }

        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId)
        val contentResolver = GlobalState.appContext.contentResolver
        val result = runCatching {
            Dog.i(TAG, "update image id=$imageId squareFit=$squareImageFit", enableLog)
            val exifOrientation = readExifOrientation(uri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, this)
                }
            }

            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            options.inSampleSize = calculateInSampleSize(options, 1080, 1920)

            val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: throw IllegalStateException("无法解码图片")
            val orientedBitmap = applyExifOrientation(bitmap, exifOrientation)
            val nativeBitmap = prepareImageForNative(orientedBitmap)

            try {
                NativeBridge.processBitmap(nativeBitmap)
            } finally {
                if (nativeBitmap !== orientedBitmap) {
                    nativeBitmap.recycle()
                }
                if (orientedBitmap !== bitmap) {
                    orientedBitmap.recycle()
                }
                bitmap.recycle()
            }
        }

        result.onSuccess { success ->
            val msg = if (success) "图片已就绪" else "图片接收失败(Native)"
            updateState(success, msg)
        }.onFailure { e ->
            val msg = when (e) {
                is SecurityException -> "无权限读取图片"
                else -> "图片传输异常(Java IO)"
            }
            updateState(false, msg)
        }
    }

    private fun prepareImageForNative(bitmap: Bitmap): Bitmap {
        if (!squareImageFit) return bitmap

        val visibleSize = min(bitmap.width, bitmap.height).let { it - (it % 2) }
        if (visibleSize <= 0) return bitmap

        val frameHeight = visibleSize
        val frameWidth = (frameHeight * INSTANT_FRAME_ASPECT_WIDTH / INSTANT_FRAME_ASPECT_HEIGHT)
            .roundToInt()
            .let { it - (it % 2) }
            .coerceAtLeast(frameHeight)

        val instantBitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(instantBitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val displayMetrics = getRealDisplayMetrics()
        val displayWidth = displayMetrics.widthPixels.coerceAtLeast(1)
        val quickSnapSurfaceHeight = getQuickSnapSurfaceHeightPx(displayWidth)
        val quickSnapMargin = getQuickSnapMarginPx()
        val quickSnapSide = (displayWidth - (quickSnapMargin * 2)).coerceAtLeast(1)
        val previewScale = max(
            displayWidth.toFloat() / frameWidth.toFloat(),
            quickSnapSurfaceHeight.toFloat() / frameHeight.toFloat()
        )
        val sampledSize = quickSnapSide.toFloat() / previewScale
        val sampledLeft = (((frameWidth * previewScale) - displayWidth) / 2f + quickSnapMargin) / previewScale
        val sampledTop = 0f

        val backgroundScale = max(
            frameWidth.toFloat() / bitmap.width.toFloat(),
            frameHeight.toFloat() / bitmap.height.toFloat()
        )
        val backgroundWidth = bitmap.width * backgroundScale
        val backgroundHeight = bitmap.height * backgroundScale
        val backgroundLeft = (frameWidth - backgroundWidth) / 2f
        val backgroundTop = (frameHeight - backgroundHeight) / 2f
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(backgroundLeft, backgroundTop, backgroundLeft + backgroundWidth, backgroundTop + backgroundHeight),
            paint
        )

        val sourceSize = (visibleSize / instantCropZoom.coerceIn(1f, 5f))
            .roundToInt()
            .coerceIn(1, visibleSize)
        val maxSourceOffsetX = ((bitmap.width - sourceSize) / 2f).coerceAtLeast(0f)
        val maxSourceOffsetY = ((bitmap.height - sourceSize) / 2f).coerceAtLeast(0f)
        val sourceCenterX = bitmap.width / 2f + instantCropOffsetX.coerceIn(-1f, 1f) * maxSourceOffsetX
        val sourceCenterY = bitmap.height / 2f + instantCropOffsetY.coerceIn(-1f, 1f) * maxSourceOffsetY
        val srcLeft = (sourceCenterX - sourceSize / 2f)
            .roundToInt()
            .coerceIn(0, bitmap.width - sourceSize)
        val srcTop = (sourceCenterY - sourceSize / 2f)
            .roundToInt()
            .coerceIn(0, bitmap.height - sourceSize)
        canvas.drawBitmap(
            bitmap,
            Rect(srcLeft, srcTop, srcLeft + sourceSize, srcTop + sourceSize),
            RectF(sampledLeft, sampledTop, sampledLeft + sampledSize, sampledTop + sampledSize),
            paint
        )
        Dog.i(
            TAG,
            "Instant compensate source ${bitmap.width}x${bitmap.height} -> ${frameWidth}x${frameHeight}, " +
                "surface=${displayWidth}x$quickSnapSurfaceHeight, final=${quickSnapSide}x$quickSnapSide, " +
                "sample=${sampledLeft.roundToInt()},0 ${sampledSize.roundToInt()}x${sampledSize.roundToInt()}, " +
                "src=$srcLeft,$srcTop ${sourceSize}x$sourceSize",
            enableLog
        )
        return instantBitmap
    }

    private fun getQuickSnapMarginPx(): Int {
        val resources = GlobalState.appContext.resources
        return runCatching {
            resources.getDimensionPixelSize(INSTAGRAM_QUICKSNAP_MARGIN_DIMEN_ID)
        }.getOrElse {
            (INSTAGRAM_QUICKSNAP_MARGIN_DP * resources.displayMetrics.density).roundToInt()
        }.coerceAtLeast(0)
    }

    @Suppress("DEPRECATION")
    private fun getRealDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = GlobalState.appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager != null) {
            windowManager.defaultDisplay.getRealMetrics(metrics)
        } else {
            metrics.setTo(GlobalState.appContext.resources.displayMetrics)
        }
        return metrics
    }

    private fun getQuickSnapSurfaceHeightPx(surfaceWidth: Int): Int {
        return (surfaceWidth * INSTANT_FRAME_ASPECT_WIDTH / INSTANT_FRAME_ASPECT_HEIGHT)
            .roundToInt()
            .coerceAtLeast(surfaceWidth)
    }

    private fun readExifOrientation(uri: Uri): Int {
        val contentResolver = GlobalState.appContext.contentResolver
        return runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun updateState(ready: Boolean, message: String) {
        mediaIsReady = ready
        toastMessage = message
    }
}
