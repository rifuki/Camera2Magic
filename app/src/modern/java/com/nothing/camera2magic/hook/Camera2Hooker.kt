package com.nothing.camera2magic.hook

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.view.Surface as AndroidSurface
import android.view.Surface
import android.view.WindowManager
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.hook.NativeBridge.needStartRenderer
import com.nothing.camera2magic.hook.NativeBridge.needStopRenderer
import com.nothing.camera2magic.hook.NativeBridge.registerSurfaceIfNew
import com.nothing.camera2magic.hook.NativeBridge.releaseLastRegisteredSurface

import com.nothing.camera2magic.utils.Dog
import com.nothing.camera2magic.utils.shortId

import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

object Camera2Hooker {
    private const val TAG = "[CAM2]"
    private const val MAX_PREVIEW_AREA = 3840 * 2160

    private val CameraDevice?.shortId : String
        get() = if (this == null) "null" else "@0x${Integer.toHexString(System.identityHashCode(this))}"
    private lateinit var magic: MagicHook
    private val hookedClasses = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>()))
    private var activeCameraRef: WeakReference<Any>? = null
    private var cameraState = WeakHashMap<CameraDevice, CameraState>()
    private data class SurfaceInfo(
        val surface: Surface,
        val width: Int,
        val height: Int,
        val format: Int
    )

    private fun getCameraState(camera: CameraDevice): CameraState {
        return synchronized(cameraState) {
            cameraState.getOrPut(camera) { CameraState() }
        }
    }

    private fun readSurfaceInfo(surface: Surface): SurfaceInfo? {
        return runCatching {
            val (width, height, format) = NativeBridge.getSurfaceInfo(surface)
            SurfaceInfo(surface, width, height, format)
        }.getOrNull()
    }

    private fun SurfaceInfo.isPreviewCandidate(): Boolean {
        val area = width.toLong() * height.toLong()

        // Camera2 preview surfaces may be exposed either as an RGB surface
        // or as an opaque/private surface.
        val supportedPreviewFormat =
            format == 1 ||
                format == android.graphics.ImageFormat.PRIVATE

        return supportedPreviewFormat &&
            area in 1..MAX_PREVIEW_AREA
    }

    private fun selectPreviewSurface(surfaces: List<Surface>): SurfaceInfo? {
        val infos = surfaces.mapNotNull(::readSurfaceInfo)
        infos.forEach { info ->
            Dog.i(
                TAG,
                "surface[${info.surface.shortId}] ${info.width}x${info.height}, format=${info.format}",
                SourceManager.enableLog
            )
        }
        return infos
            .filter { it.isPreviewCandidate() }
            .minByOrNull { it.width.toLong() * it.height.toLong() }
    }

    private fun CameraState.saveCameraInfo(camera: CameraDevice) {
        val cameraIdStr = camera.id
        val context = GlobalState.appContext
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cm.getCameraCharacteristics(cameraIdStr)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val rotation = wm.defaultDisplay.rotation
        val deviceDegrees = when (rotation) {
            AndroidSurface.ROTATION_90 -> 90
            AndroidSurface.ROTATION_180 -> 180
            AndroidSurface.ROTATION_270 -> 270
            else -> 0
        }
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val facingFront = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val displayOrientation = if (facingFront) {
            (360 - ((sensorOrientation + deviceDegrees) % 360)) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }

        this.apiLevel = 2
        this.sensorOrientation = sensorOrientation
        this.facingFront = facingFront
        this.displayOrientation = displayOrientation
        this.packageName = GlobalState.packageName
        Dog.i(
            TAG,
            "camera[$cameraIdStr] sensor=$sensorOrientation device=$deviceDegrees display=$displayOrientation front=$facingFront",
            SourceManager.enableLog
        )
    }
    private fun CameraState.bindSurface(info: SurfaceInfo) {
        val surface = info.surface
        val width = info.width
        val height = info.height
        Dog.i(TAG, "bind preview surface[${surface.shortId}] ${width}x${height}", SourceManager.enableLog)
        this.pictureWidth = width
        this.pictureHeight = height
        this.previewWidth = width
        this.previewHeight = height
        this.surface = surface
    }
    private fun drawDirectPreview(surface: Surface): Boolean {
        val bitmap = SourceManager.directPreviewBitmap

        if (bitmap == null || bitmap.isRecycled) {
            return false
        }

        var canvas: android.graphics.Canvas? = null

        return try {
            canvas = try {
                surface.lockHardwareCanvas()
            } catch (_: Throwable) {
                surface.lockCanvas(null)
            }

            val target = canvas ?: return false

            target.drawColor(android.graphics.Color.BLACK)

            val paint = android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG or
                    android.graphics.Paint.FILTER_BITMAP_FLAG
            )

            target.drawBitmap(
                bitmap,
                android.graphics.Rect(
                    0,
                    0,
                    bitmap.width,
                    bitmap.height
                ),
                android.graphics.Rect(
                    0,
                    0,
                    target.width,
                    target.height
                ),
                paint
            )

            surface.unlockCanvasAndPost(target)
            canvas = null

            true
        } catch (error: Throwable) {
            Dog.e(
                TAG,
                "Unable to draw virtual camera preview.",
                error,
                SourceManager.enableLog
            )

            canvas?.let {
                runCatching {
                    surface.unlockCanvasAndPost(it)
                }
            }

            false
        }
    }

    private fun scheduleRendererStart(
        camera: CameraDevice,
        attempt: Int = 0,
        handler: android.os.Handler = android.os.Handler(
            android.os.Looper.myLooper()
                ?: android.os.Looper.getMainLooper()
        )
    ) {
        if (
            activeCameraRef?.get() !== camera ||
            !SourceManager.isReadyForHook()
        ) {
            return
        }

        // Re-fetch the state because applications may replace their preview
        // Surface while the Camera2 session is being configured.
        val state = getCameraState(camera)
        val previewSurface = state.surface

        if (previewSurface?.isValid == true) {
            if (!drawDirectPreview(previewSurface)) {
                registerSurfaceIfNew(state, true)
                needStartRenderer()
            }

            return
        }

        val maxAttempts = 30

        if (attempt >= maxAttempts) {
            Dog.e(
                TAG,
                "Preview surface remained invalid after $maxAttempts retries.",
                null,
                SourceManager.enableLog
            )
            return
        }

        handler.postDelayed({
            scheduleRendererStart(
                camera,
                attempt + 1,
                handler
            )
        }, 50L)
    }

    private fun handleStateCallback(callback: CameraCaptureSession.StateCallback) {
        val clazz = callback.javaClass
        if (hookedClasses.add(clazz)) {
            val onConfigured = clazz.getDeclaredMethod("onConfigured",
                CameraCaptureSession::class.java)

            magic.hook(onConfigured).intercept { chain ->
                val session = chain.args[0] as CameraCaptureSession
                val camera = session.device
                val result = chain.proceed()
                val activeCamera = activeCameraRef?.get()
                if (activeCamera === camera && SourceManager.isReadyForHook()) {
                    scheduleRendererStart(camera)
                }
                result
            }

            val onConfigureFailed = clazz.getDeclaredMethod("onConfigureFailed",
                CameraCaptureSession::class.java)

            magic.hook(onConfigureFailed).intercept { chain ->
                Dog.e(TAG, "CameraCaptureSession.StateCallback: onConfigureFailed.", null, true)
                BlackHoleMapper.clearAll()
                activeCameraRef = null
                chain.proceed()
            }
        }
    }
    private fun hookImageReaderJpegAcquire() {
        val acquireNextImage =
            android.media.ImageReader::class.java.getDeclaredMethod(
                "acquireNextImage"
            )

        magic.hook(acquireNextImage).intercept { chain ->
            val result = chain.proceed()

            val image = result as? android.media.Image
                ?: return@intercept result

            if (
                !SourceManager.isReadyForHook() ||
                activeCameraRef?.get() == null ||
                image.format != android.graphics.ImageFormat.JPEG
            ) {
                return@intercept result
            }

            runCatching {
                val bitmap = SourceManager.directPreviewBitmap
                    ?: throw IllegalStateException(
                        "No virtual camera bitmap available"
                    )

                if (bitmap.isRecycled) {
                    throw IllegalStateException(
                        "Virtual camera bitmap is recycled"
                    )
                }

                val output = java.io.ByteArrayOutputStream()

                if (
                    !bitmap.compress(
                        android.graphics.Bitmap.CompressFormat.JPEG,
                        92,
                        output
                    )
                ) {
                    throw IllegalStateException(
                        "Bitmap.compress(JPEG) failed"
                    )
                }

                val replacement = output.toByteArray()

                if (replacement.isEmpty()) {
                    throw IllegalStateException(
                        "Generated JPEG is empty"
                    )
                }

                val plane = image.planes.firstOrNull()
                    ?: throw IllegalStateException(
                        "JPEG image has no plane"
                    )

                val buffer = plane.buffer

                if (buffer.isReadOnly) {
                    throw IllegalStateException(
                        "JPEG plane buffer is read-only"
                    )
                }

                if (replacement.size > buffer.capacity()) {
                    throw IllegalStateException(
                        "Replacement JPEG exceeds capture buffer capacity"
                    )
                }

                buffer.clear()
                buffer.put(replacement)
                buffer.flip()
            }.onFailure { error ->
                Dog.e(
                    TAG,
                    "Unable to replace Camera2 JPEG: ${error.message}",
                    error,
                    SourceManager.enableLog
                )
            }

            result
        }
    }

    @SuppressLint("PrivateApi")
    fun initHooks(module: MagicHook, param: PackageReadyParam) {
        magic = module
        val classLoader = param.classLoader
        val deviceImplClass = classLoader.loadClass("android.hardware.camera2.impl.CameraDeviceImpl")
        deviceImplClass.apply {
            hookCreateCaptureSessionWithConfiguration()
            hookCreateCaptureSessionWithSurfaces()
            hookClose()
        }
        val builderClass = classLoader.loadClass("android.hardware.camera2.CaptureRequest\$Builder")
        builderClass.apply {
            hookAddTarget()
            hookRemoveTarget()
        }

        hookImageReaderJpegAcquire()
    }

    private fun Class<*>.hookCreateCaptureSessionWithConfiguration() {
        val createCaptureSession = getDeclaredMethod(
            "createCaptureSession",
            SessionConfiguration::class.java)
        magic.hook(createCaptureSession).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val camera = chain.thisObject as CameraDevice
            activeCameraRef = WeakReference(camera)

            val state = getCameraState(camera)
            state.saveCameraInfo(camera)

            val sessionConfiguration = chain.args[0] as SessionConfiguration
            val previewSurface = selectPreviewSurface(
                sessionConfiguration.outputConfigurations.flatMap { it.surfaces }
            )

            @SuppressLint("SoonBlockedPrivateApi")
            val field = OutputConfiguration::class.java.getDeclaredField("mSurfaces")
            field.isAccessible = true
            sessionConfiguration.outputConfigurations.forEach { outputConfiguration ->
                var modified = false
                val surfaces = outputConfiguration.surfaces
                val modifiedSurfaces = surfaces.mapTo(ArrayList<Surface>()) { origin ->
                    if (origin === previewSurface?.surface) {
                        modified = true
                        state.bindSurface(previewSurface)
                        return@mapTo BlackHoleMapper.createBlackHole(
                            origin,
                            previewSurface.width,
                            previewSurface.height
                        )
                    }
                    origin
                }
                if (modified) field.set(outputConfiguration, modifiedSurfaces)
            }
            handleStateCallback(sessionConfiguration.stateCallback)
            chain.proceed()
        }
    }

    private fun Class<*>.hookCreateCaptureSessionWithSurfaces() {
        val createCaptureSession = getDeclaredMethod(
            "createCaptureSession",
            List::class.java,
            CameraCaptureSession.StateCallback::class.java,
            Handler::class.java)
        magic.hook(createCaptureSession).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val camera = chain.thisObject as CameraDevice
            activeCameraRef = WeakReference(camera)
            val state = getCameraState(camera)
            state.saveCameraInfo(camera)
            @Suppress("UNCHECKED_CAST")
            val surfaces = chain.args[0] as List<Surface>
            val previewSurface = selectPreviewSurface(surfaces)
            val newList = surfaces.mapTo(ArrayList()) { origin ->
                if (origin === previewSurface?.surface) {
                    state.bindSurface(previewSurface)
                    return@mapTo BlackHoleMapper.createBlackHole(
                        origin,
                        previewSurface.width,
                        previewSurface.height
                    )
                }
                origin
            }

            val stateCallback = chain.args[1] as CameraCaptureSession.StateCallback
            handleStateCallback(stateCallback)

            val newArgs = chain.args.toTypedArray()
            newArgs[0] = newList
            chain.proceed(newArgs)
        }
    }

    private fun Class<*>.hookClose() {
        val close = getDeclaredMethod("close")
        magic.hook(close).intercept { chain ->
            val activeCamera = activeCameraRef?.get()
            val closingCamera = chain.thisObject as CameraDevice

            if (activeCamera != null && closingCamera === activeCamera) {
                Dog.i(TAG, "camera[${closingCamera.shortId}] close.", true)
                needStopRenderer()
                releaseLastRegisteredSurface()
                BlackHoleMapper.clearAll()
                activeCameraRef = null
            }
            chain.proceed()
        }
    }

    private fun Class<*>.hookAddTarget() {
        val addTarget = getDeclaredMethod(
            "addTarget",
            Surface::class.java
        )

        magic.hook(addTarget).intercept { chain ->
            val origin = chain.args[0] as Surface

            if (!SourceManager.isReadyForHook()) {
                return@intercept chain.proceed()
            }

            val blackHole =
                BlackHoleMapper.getBlackHole(origin) ?: run {
                    val info = readSurfaceInfo(origin)

                    if (info?.isPreviewCandidate() == true) {
                        Dog.i(
                            TAG,
                            "early map request target[${origin.shortId}] " +
                                "${info.width}x${info.height}",
                            SourceManager.enableLog
                        )

                        BlackHoleMapper.createBlackHole(
                            origin,
                            info.width,
                            info.height
                        )
                    } else {
                        null
                    }
                } ?: return@intercept chain.proceed()

            chain.proceed(arrayOf(blackHole))
        }
    }

    private fun Class<*>.hookRemoveTarget() {
        val removeTarget = getDeclaredMethod("removeTarget", Surface::class.java)
        magic.hook(removeTarget).intercept { chain ->
            val origin = chain.args[0] as Surface
            val blackHole = BlackHoleMapper.getBlackHole(origin)
            if (!SourceManager.isReadyForHook() || blackHole == null) {
                return@intercept chain.proceed()
            }
            chain.proceed(arrayOf(blackHole))
        }
    }
}
