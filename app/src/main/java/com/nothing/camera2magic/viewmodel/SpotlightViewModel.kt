package com.nothing.camera2magic.viewmodel

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.Exception

data class SpotlightUiState(
    val moduleEnabled: Boolean = true,
    val selectedMediaSource: MediaSource = MediaSource.LOCAL,
    val currentType: MediaType = MediaType.VIDEO,
    val squareImageFit: Boolean = false,
    val instantCropZoom: Float = 1f,
    val instantCropOffsetX: Float = 0f,
    val instantCropOffsetY: Float = 0f,
)

class SpotlightViewModel(
    private val app: Application,
    private val repository: ConfigRepository
) : ViewModel() {

    private val _thumbnails = MutableStateFlow<Map<MediaType, Bitmap?>>(emptyMap())
    val thumbnails = _thumbnails.asStateFlow()

    private val _uiState = MutableStateFlow(SpotlightUiState())
    val uiState = _uiState.asStateFlow()

    private var instantCropSaveJob: Job? = null

    init {
        loadInitialSettings()
        performHealthCheckAndRefresh()
    }

    fun onModuleToggled() {
        _uiState.update { currentState ->
            val newState = !currentState.moduleEnabled
            repository.moduleEnabled = newState
            currentState.copy(moduleEnabled = newState)
        }
    }
    fun selectedMediaSourceFrom(value: Int) {
        val source = MediaSource.fromValue(value)
        _uiState.update { currentState ->
            repository.mediaSource = value
            currentState.copy(selectedMediaSource = source)
        }
    }

    fun setCurrentMediaType(type: MediaType) {
        _uiState.update { currentState ->
            repository.localMediaType = type.value
            currentState.copy(currentType = type)
        }
    }

    fun onSquareImageFitToggled() {
        _uiState.update { currentState ->
            val newState = !currentState.squareImageFit
            repository.squareImageFit = newState
            currentState.copy(squareImageFit = newState)
        }
    }

    fun onInstantCropChanged(zoom: Float, offsetX: Float, offsetY: Float) {
        val safeZoom = zoom.coerceIn(1f, 5f)
        val safeOffsetX = offsetX.coerceIn(-1f, 1f)
        val safeOffsetY = offsetY.coerceIn(-1f, 1f)
        _uiState.update { currentState ->
            currentState.copy(
                instantCropZoom = safeZoom,
                instantCropOffsetX = safeOffsetX,
                instantCropOffsetY = safeOffsetY
            )
        }
        instantCropSaveJob?.cancel()
        instantCropSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(180)
            repository.setInstantCrop(safeZoom, safeOffsetX, safeOffsetY)
        }
    }

    fun resetInstantCrop() {
        instantCropSaveJob?.cancel()
        repository.setInstantCrop(1f, 0f, 0f)
        onInstantCropChanged(1f, 0f, 0f)
    }

    fun onMediaSelected(type: MediaType, uri: Uri?) {
        if (uri == null) return
        val mediaId = resolveMediaId(type, uri) ?: importMediaToStore(type, uri)
        if (mediaId != null) {
            saveMediaId(type, mediaId)
            if (type == MediaType.IMAGE) {
                instantCropSaveJob?.cancel()
                repository.setInstantCrop(1f, 0f, 0f)
                _uiState.update {
                    it.copy(
                        instantCropZoom = 1f,
                        instantCropOffsetX = 0f,
                        instantCropOffsetY = 0f
                    )
                }
            }
            loadAndVerifyMedia(type, mediaId)
        }
    }
    fun clearMediaBy(type: MediaType) {
        when (type) {
            MediaType.VIDEO -> repository.videoId = -1L
            MediaType.IMAGE -> repository.imageId = -1L
        }
        updateThumbnailState(type, null)
    }
    fun performHealthCheckAndRefresh() {
        MediaType.entries.forEach { type ->
            loadAndVerifyMedia(type)
        }
    }

    private fun saveMediaId(type: MediaType, id: Long) {
        when (type) {
            MediaType.VIDEO -> repository.videoId = id
            MediaType.IMAGE -> repository.imageId = id
        }
    }
    private fun loadInitialSettings() {
        _uiState.update {
            it.copy(
                moduleEnabled = repository.moduleEnabled,
                selectedMediaSource = MediaSource.fromValue(repository.mediaSource),
                currentType = MediaType.fromValue(repository.localMediaType),
                squareImageFit = repository.squareImageFit,
                instantCropZoom = repository.instantCropZoom.coerceIn(1f, 5f),
                instantCropOffsetX = repository.instantCropOffsetX.coerceIn(-1f, 1f),
                instantCropOffsetY = repository.instantCropOffsetY.coerceIn(-1f, 1f)
            )
        }
    }
    private fun getMediaId(type: MediaType): Long {
        return when (type) {
            MediaType.VIDEO -> repository.videoId
            MediaType.IMAGE -> repository.imageId
        }
    }

    private fun resolveMediaId(type: MediaType, uri: Uri): Long? {
        directMediaId(type, uri)?.takeIf { canOpenMediaId(type, it) }?.let { return it }

        val documentId = runCatching {
            if (DocumentsContract.isDocumentUri(app, uri)) {
                DocumentsContract.getDocumentId(uri)
            } else {
                null
            }
        }.getOrNull()
        documentId
            ?.takeIf { it.startsWith(type.documentPrefix) }
            ?.substringAfter(':')
            ?.toLongOrNull()
            ?.takeIf { canOpenMediaId(type, it) }
            ?.let { return it }

        val projection = when (type) {
            MediaType.VIDEO -> arrayOf(MediaStore.Video.Media._ID)
            MediaType.IMAGE -> arrayOf(MediaStore.Images.Media._ID)
        }
        return runCatching {
            app.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0).takeIf { canOpenMediaId(type, it) }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private val MediaType.documentPrefix: String
        get() = when (this) {
            MediaType.VIDEO -> "video:"
            MediaType.IMAGE -> "image:"
        }

    private val MediaType.storeUri: Uri
        get() = when (this) {
            MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    private fun directMediaId(type: MediaType, uri: Uri): Long? {
        if (uri.authority != MediaStore.AUTHORITY) return null
        val segments = uri.pathSegments
        val typeSegment = when (type) {
            MediaType.VIDEO -> "video"
            MediaType.IMAGE -> "images"
        }
        if (!segments.contains(typeSegment) || !segments.contains("media")) return null
        return runCatching { ContentUris.parseId(uri) }.getOrNull()
    }

    private fun canOpenMediaId(type: MediaType, id: Long): Boolean {
        val uri = ContentUris.withAppendedId(type.storeUri, id)
        return runCatching {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
        }.getOrDefault(false)
    }

    private fun importMediaToStore(type: MediaType, uri: Uri): Long? {
        val resolver = app.contentResolver
        val sourceName = displayNameFor(uri) ?: when (type) {
            MediaType.VIDEO -> "camera2magic_${System.currentTimeMillis()}.mp4"
            MediaType.IMAGE -> "camera2magic_${System.currentTimeMillis()}.jpg"
        }
        val mimeType = resolver.getType(uri) ?: when (type) {
            MediaType.VIDEO -> "video/mp4"
            MediaType.IMAGE -> "image/jpeg"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sourceName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    when (type) {
                        MediaType.VIDEO -> "${Environment.DIRECTORY_MOVIES}/Camera2Magic"
                        MediaType.IMAGE -> "${Environment.DIRECTORY_PICTURES}/Camera2Magic"
                    }
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val destination = resolver.insert(type.storeUri, values) ?: return null
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                resolver.openOutputStream(destination)?.use { output ->
                    input.copyTo(output)
                } ?: throw IllegalStateException("Cannot write imported media")
            } ?: throw IllegalStateException("Cannot read selected media")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    destination,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            ContentUris.parseId(destination)
        }.getOrElse {
            resolver.delete(destination, null, null)
            null
        }
    }

    private fun displayNameFor(uri: Uri): String? {
        return runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun loadAndVerifyMedia(type: MediaType, mediaIdOverride: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val mediaId = mediaIdOverride ?: getMediaId(type)
            if (mediaId == -1L) {
                updateThumbnailState(type, null)
                return@launch
            }

            var thumbnail: Bitmap? = null
            var isMediaValid = false

            try {
                val contentUri = when (type) {
                    MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val uri = ContentUris.withAppendedId(contentUri, mediaId)
                app.contentResolver.openFileDescriptor(uri, "r")?.use {
                    isMediaValid = true
                    thumbnail = when (type) {
                        MediaType.VIDEO -> app.contentResolver.loadThumbnail(uri, Size(720, 1280), null)
                        MediaType.IMAGE -> decodeImagePreview(uri)
                    }
                }
            } catch (_: Exception) {
                isMediaValid = false
            }
            if (isMediaValid) {
                updateThumbnailState(type, thumbnail)
            } else {
                updateThumbnailState(type, null)
                if (mediaIdOverride == null) {
                    saveMediaId(type, -1L)
                }
            }
        }
    }
    private fun updateThumbnailState(type: MediaType, thumbnail: Bitmap?) {
        _thumbnails.update { currentMap ->
            currentMap + (type to thumbnail)
        }
    }

    private fun decodeImagePreview(uri: Uri): Bitmap? {
        return runCatching {
            val exifOrientation = readExifOrientation(uri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                app.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, this)
                }
            }

            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            options.inSampleSize = calculatePreviewInSampleSize(options, 2560, 2560)

            val bitmap = app.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: throw IllegalStateException("Cannot decode image preview")
            val orientedBitmap = applyExifOrientation(bitmap, exifOrientation)
            if (orientedBitmap !== bitmap) {
                bitmap.recycle()
            }
            orientedBitmap
        }.getOrNull()
    }

    private fun readExifOrientation(uri: Uri): Int {
        return runCatching {
            app.contentResolver.openInputStream(uri)?.use { stream ->
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

    private fun calculatePreviewInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        while (options.outHeight / inSampleSize > reqHeight || options.outWidth / inSampleSize > reqWidth) {
            inSampleSize *= 2
        }
        return inSampleSize
    }
}
