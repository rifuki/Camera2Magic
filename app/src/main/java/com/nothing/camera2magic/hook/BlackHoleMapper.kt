package com.nothing.camera2magic.hook

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.nothing.camera2magic.utils.Dog
import java.util.WeakHashMap

data class BlackHole(
    val identityId: Int,
    val width: Int,
    val height: Int,
    val surface: Surface,
    val reader: ImageReader
)
private const val TAG = "[BlackHole]"
object BlackHoleMapper {
    private val oabMap = WeakHashMap<Surface, BlackHole>()
    private val camera3Thread = HandlerThread("camera3Thread").apply { start() }
    private val camera3Handler = Handler(camera3Thread.looper)
    fun createBlackHole(origin: Surface, width: Int, height: Int): Surface {
        oabMap[origin]?.let {
            return it.surface
        }

        // The same logical camera output can be represented by different
        // Surface wrappers between addTarget() and session configuration.
        // Reuse an existing matching output so CaptureRequest and session
        // configuration reference the same Surface.
        oabMap.values.firstOrNull {
            it.width == width && it.height == height
        }?.let { reusable ->
            oabMap[origin] = reusable

            Dog.i(
                TAG,
                "reuse blackhole[${reusable.identityId}] for ${width}x${height}",
                SourceManager.enableLog
            )

            return reusable.surface
        }

        val id = 20 + oabMap.values.toSet().size

        val reader = ImageReader.newInstance(
            width,
            height,
            ImageFormat.PRIVATE,
            4
        )

        reader.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireLatestImage()?.close()
            }.onFailure { exception ->
                Dog.e(
                    TAG,
                    "acquireLatestImage Failed: ${exception.message}",
                    exception,
                    SourceManager.enableLog
                )
            }
        }, camera3Handler)

        Dog.i(
            TAG,
            "blackhole[$id] ${width}x${height}, format=PRIVATE",
            SourceManager.enableLog
        )

        val blackHole = BlackHole(
            id,
            width,
            height,
            reader.surface,
            reader
        )

        oabMap[origin] = blackHole
        return blackHole.surface
    }

    fun getBlackHole(origin: Surface): Surface? {
        return oabMap[origin]?.surface
    }

    fun clearAll() {
        oabMap.values
            .toSet()
            .forEach { it.reader.close() }

        oabMap.clear()
    }
}
