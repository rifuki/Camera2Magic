package com.nothing.camera2magic.view

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nothing.camera2magic.viewmodel.SpotlightViewModel
import com.nothing.camera2magic.R
import com.nothing.camera2magic.viewmodel.LocalViewModelFactory
import com.nothing.camera2magic.viewmodel.MediaSource
import com.nothing.camera2magic.viewmodel.MediaType
import kotlin.enums.EnumEntries
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotlightView() {
    val mediaSources = MediaSource.entries
    val mediaSourceLabels = stringArrayResource(R.array.media_source)
    val mediaTypes = MediaType.entries

    val factory = LocalViewModelFactory.current
    val viewModel: SpotlightViewModel = viewModel(factory = factory)

    val mediaThumbnails by viewModel.thumbnails.collectAsState()

    val uiState by viewModel.uiState.collectAsState()

    var pendingType by remember { mutableStateOf<MediaType?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        pendingType?.let { type ->
            viewModel.onMediaSelected(type, it)
        }
    }

    val pickMedia = { type: MediaType ->
        pendingType = type
        launcher.launch(type.mimeType)
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            MediaSourceSelector(
                sources = mediaSources,
                labels = mediaSourceLabels,
                selectedIndex = uiState.selectedMediaSource.value,
                onSourceSelected = { index -> viewModel.selectedMediaSourceFrom(index) }
            )
            Spacer(modifier = Modifier.height(16.dp))
            MediaPreviewGrid(
                mediaTypes = mediaTypes,
                thumbnails = mediaThumbnails,
                currentType = uiState.currentType,
                squareImageFit = uiState.squareImageFit,
                instantCropZoom = uiState.instantCropZoom,
                instantCropOffsetX = uiState.instantCropOffsetX,
                instantCropOffsetY = uiState.instantCropOffsetY,
                onPickMedia = { type -> pickMedia(type) },
                onClearMedia = { type -> viewModel.clearMediaBy(type)},
                onTypeSelected = { type -> viewModel.setCurrentMediaType(type) }

            )
            InstantFitToggle(
                enabled = uiState.currentType == MediaType.IMAGE,
                checked = uiState.squareImageFit,
                onToggle = { viewModel.onSquareImageFitToggled() }
            )
            InstantCropEditor(
                visible = uiState.currentType == MediaType.IMAGE && uiState.squareImageFit,
                thumbnail = mediaThumbnails[MediaType.IMAGE],
                zoom = uiState.instantCropZoom,
                offsetX = uiState.instantCropOffsetX,
                offsetY = uiState.instantCropOffsetY,
                onCropChanged = viewModel::onInstantCropChanged,
                onReset = viewModel::resetInstantCrop
            )
            ModuleSwitch(
                text = stringResource(R.string.module_switch_name),
                isEnabled = uiState.moduleEnabled,
                onToggle = { viewModel.onModuleToggled() }
            )
        }
    }
    OnLifecycleEvent { event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            viewModel.performHealthCheckAndRefresh()
        }
    }
}

@Composable
private fun MediaSourceSelector(
    sources: EnumEntries<MediaSource>,
    labels: Array<String>,
    selectedIndex: Int,
    onSourceSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
){
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        sources.forEachIndexed { index, source ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = sources.size),
                onClick = { onSourceSelected(index) },
                selected = index == selectedIndex,
                enabled = source != MediaSource.NETWORK
            ) {
                Text(labels[index])
            }
        }
    }
}

@Composable
private fun MediaPreviewGrid(
    mediaTypes: EnumEntries<MediaType>,
    thumbnails: Map<MediaType, Bitmap?>,
    currentType: MediaType,
    squareImageFit: Boolean,
    instantCropZoom: Float,
    instantCropOffsetX: Float,
    instantCropOffsetY: Float,
    onPickMedia: (MediaType) -> Unit,
    onClearMedia: (MediaType) -> Unit,
    onTypeSelected: (MediaType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        mediaTypes.forEach { type ->
            Column(modifier = Modifier.weight(1f)) {
                MediaThumbnailCard(
                    thumbnail = thumbnails[type],
                    mediaType = type,
                    instantPreview = squareImageFit && type == MediaType.IMAGE,
                    instantCropZoom = instantCropZoom,
                    instantCropOffsetX = instantCropOffsetX,
                    instantCropOffsetY = instantCropOffsetY,
                    onClick = { onPickMedia(type) },
                    onClear = { onClearMedia(type) }
                )
                RadioButtonRow(
                    selected = currentType == type,
                    onClick = { onTypeSelected(type) },
                )
            }
        }
    }
}

@Composable
private fun InstantCropEditor(
    visible: Boolean,
    thumbnail: Bitmap?,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    onCropChanged: (Float, Float, Float) -> Unit,
    onReset: () -> Unit
) {
    var showEditor by remember { mutableStateOf(false) }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.instant_crop_editor_name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.instant_crop_reset_button_name))
                }
            }
            if (thumbnail == null) {
                Text(
                    text = stringResource(R.string.instant_crop_editor_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            Text(
                text = stringResource(R.string.instant_crop_editor_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { showEditor = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.instant_crop_open_button_name))
            }
        }
    }
    if (showEditor && thumbnail != null) {
        InstantCropDialog(
            thumbnail = thumbnail,
            zoom = zoom,
            offsetX = offsetX,
            offsetY = offsetY,
            onCropChanged = onCropChanged,
            onReset = onReset,
            onDismiss = { showEditor = false }
        )
    }
}

private data class CropGeometry(
    val imageScale: Float,
    val imageLeft: Float,
    val imageTop: Float,
    val imageWidth: Float,
    val imageHeight: Float,
    val centerX: Float,
    val centerY: Float,
    val sourceSize: Float,
    val cropLeft: Float,
    val cropTop: Float,
    val cropSize: Float
)

private enum class CropDragMode { None, Move, ResizeTopLeft, ResizeTopRight, ResizeBottomLeft, ResizeBottomRight }

private fun sourceSizeFor(bitmap: Bitmap, zoom: Float): Float {
    return min(bitmap.width, bitmap.height) / zoom.coerceIn(1f, 5f)
}

private fun cropCenter(bitmap: Bitmap, zoom: Float, offsetX: Float, offsetY: Float): Pair<Float, Float> {
    val sourceSize = sourceSizeFor(bitmap, zoom)
    val maxOffsetX = ((bitmap.width - sourceSize) / 2f).coerceAtLeast(0f)
    val maxOffsetY = ((bitmap.height - sourceSize) / 2f).coerceAtLeast(0f)
    return bitmap.width / 2f + offsetX.coerceIn(-1f, 1f) * maxOffsetX to
        bitmap.height / 2f + offsetY.coerceIn(-1f, 1f) * maxOffsetY
}

private fun normalizedOffsets(bitmap: Bitmap, zoom: Float, centerX: Float, centerY: Float): Pair<Float, Float> {
    val sourceSize = sourceSizeFor(bitmap, zoom)
    val maxOffsetX = ((bitmap.width - sourceSize) / 2f).coerceAtLeast(0f)
    val maxOffsetY = ((bitmap.height - sourceSize) / 2f).coerceAtLeast(0f)
    val nextOffsetX = if (maxOffsetX > 0f) {
        ((centerX.coerceIn(sourceSize / 2f, bitmap.width - sourceSize / 2f) - bitmap.width / 2f) / maxOffsetX)
            .coerceIn(-1f, 1f)
    } else {
        0f
    }
    val nextOffsetY = if (maxOffsetY > 0f) {
        ((centerY.coerceIn(sourceSize / 2f, bitmap.height - sourceSize / 2f) - bitmap.height / 2f) / maxOffsetY)
            .coerceIn(-1f, 1f)
    } else {
        0f
    }
    return nextOffsetX to nextOffsetY
}

private fun cropGeometry(size: IntSize, bitmap: Bitmap, zoom: Float, offsetX: Float, offsetY: Float): CropGeometry {
    if (size.width <= 0 || size.height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) {
        return CropGeometry(1f, 0f, 0f, 0f, 0f, bitmap.width / 2f, bitmap.height / 2f, 0f, 0f, 0f, 0f)
    }
    val imageScale = min(size.width.toFloat() / bitmap.width.toFloat(), size.height.toFloat() / bitmap.height.toFloat())
    val imageWidth = bitmap.width * imageScale
    val imageHeight = bitmap.height * imageScale
    val imageLeft = (size.width - imageWidth) / 2f
    val imageTop = (size.height - imageHeight) / 2f
    val sourceSize = sourceSizeFor(bitmap, zoom)
    val (centerX, centerY) = cropCenter(bitmap, zoom, offsetX, offsetY)
    val sourceLeft = (centerX - sourceSize / 2f).coerceIn(0f, bitmap.width - sourceSize)
    val sourceTop = (centerY - sourceSize / 2f).coerceIn(0f, bitmap.height - sourceSize)
    val cropSize = sourceSize * imageScale
    return CropGeometry(
        imageScale = imageScale,
        imageLeft = imageLeft,
        imageTop = imageTop,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        centerX = centerX,
        centerY = centerY,
        sourceSize = sourceSize,
        cropLeft = imageLeft + sourceLeft * imageScale,
        cropTop = imageTop + sourceTop * imageScale,
        cropSize = cropSize
    )
}

@Composable
private fun InstantCropDialog(
    thumbnail: Bitmap,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    onCropChanged: (Float, Float, Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.instant_crop_editor_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row {
                        TextButton(onClick = onReset) {
                            Text(stringResource(R.string.instant_crop_reset_button_name))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.instant_crop_done_button_name))
                        }
                    }
                }
                InstantCropCanvas(
                    thumbnail = thumbnail,
                    zoom = zoom,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    onCropChanged = onCropChanged,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.instant_crop_editor_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.instant_crop_zoom_label),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(48.dp)
                    )
                    Slider(
                        value = 1f / zoom.coerceIn(1f, 5f),
                        onValueChange = { nextSize ->
                            val nextZoom = (1f / nextSize).coerceIn(1f, 5f)
                            val (centerX, centerY) = cropCenter(thumbnail, zoom, offsetX, offsetY)
                            val (nextOffsetX, nextOffsetY) = normalizedOffsets(thumbnail, nextZoom, centerX, centerY)
                            onCropChanged(nextZoom, nextOffsetX, nextOffsetY)
                        },
                        valueRange = 0.2f..1f,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun InstantCropCanvas(
    thumbnail: Bitmap,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    onCropChanged: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val imageBitmap = remember(thumbnail) { thumbnail.asImageBitmap() }
    val currentZoom = rememberUpdatedState(zoom)
    val currentOffsetX = rememberUpdatedState(offsetX)
    val currentOffsetY = rememberUpdatedState(offsetY)
    val currentOnCropChanged = rememberUpdatedState(onCropChanged)
    val handleTouchRadius = with(LocalDensity.current) { 56.dp.toPx() }
    val handleColor = MaterialTheme.colorScheme.primary

    fun isInsideCrop(position: Offset, geometry: CropGeometry): Boolean {
        val cropRight = geometry.cropLeft + geometry.cropSize
        val cropBottom = geometry.cropTop + geometry.cropSize
        return position.x in geometry.cropLeft..cropRight && position.y in geometry.cropTop..cropBottom
    }

    fun isNear(position: Offset, x: Float, y: Float): Boolean {
        return abs(position.x - x) <= handleTouchRadius && abs(position.y - y) <= handleTouchRadius
    }

    fun hitTest(position: Offset, geometry: CropGeometry): CropDragMode {
        val cropRight = geometry.cropLeft + geometry.cropSize
        val cropBottom = geometry.cropTop + geometry.cropSize
        return when {
            isNear(position, geometry.cropLeft, geometry.cropTop) -> CropDragMode.ResizeTopLeft
            isNear(position, cropRight, geometry.cropTop) -> CropDragMode.ResizeTopRight
            isNear(position, geometry.cropLeft, cropBottom) -> CropDragMode.ResizeBottomLeft
            isNear(position, cropRight, cropBottom) -> CropDragMode.ResizeBottomRight
            isInsideCrop(position, geometry) -> CropDragMode.Move
            else -> CropDragMode.None
        }
    }

    fun resizeFromCorner(mode: CropDragMode, geometry: CropGeometry, dragAmount: Offset) {
        if (geometry.imageScale <= 0f) return
        val dx = dragAmount.x / geometry.imageScale
        val dy = dragAmount.y / geometry.imageScale
        val signedDelta = when (mode) {
            CropDragMode.ResizeTopLeft -> if (abs(dx) > abs(dy)) -dx else -dy
            CropDragMode.ResizeTopRight -> if (abs(dx) > abs(dy)) dx else -dy
            CropDragMode.ResizeBottomLeft -> if (abs(dx) > abs(dy)) -dx else dy
            CropDragMode.ResizeBottomRight -> if (abs(dx) > abs(dy)) dx else dy
            else -> 0f
        }
        val minBitmapSide = min(thumbnail.width, thumbnail.height).toFloat()
        val nextSourceSize = (geometry.sourceSize + signedDelta).coerceIn(minBitmapSide / 5f, minBitmapSide)
        val sourceLeft = (geometry.cropLeft - geometry.imageLeft) / geometry.imageScale
        val sourceTop = (geometry.cropTop - geometry.imageTop) / geometry.imageScale
        val sourceRight = sourceLeft + geometry.sourceSize
        val sourceBottom = sourceTop + geometry.sourceSize
        val (nextLeft, nextTop) = when (mode) {
            CropDragMode.ResizeTopLeft -> sourceRight - nextSourceSize to sourceBottom - nextSourceSize
            CropDragMode.ResizeTopRight -> sourceLeft to sourceBottom - nextSourceSize
            CropDragMode.ResizeBottomLeft -> sourceRight - nextSourceSize to sourceTop
            else -> sourceLeft to sourceTop
        }
        val clampedLeft = nextLeft.coerceIn(0f, thumbnail.width - nextSourceSize)
        val clampedTop = nextTop.coerceIn(0f, thumbnail.height - nextSourceSize)
        val nextCenterX = clampedLeft + nextSourceSize / 2f
        val nextCenterY = clampedTop + nextSourceSize / 2f
        val nextZoom = (minBitmapSide / nextSourceSize).coerceIn(1f, 5f)
        val (nextOffsetX, nextOffsetY) = normalizedOffsets(thumbnail, nextZoom, nextCenterX, nextCenterY)
        currentOnCropChanged.value(nextZoom, nextOffsetX, nextOffsetY)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .onSizeChanged { viewportSize = it }
            .pointerInput(thumbnail, viewportSize) {
                var dragMode = CropDragMode.None
                detectDragGestures(
                    onDragStart = { start ->
                        val geometry = cropGeometry(
                            viewportSize,
                            thumbnail,
                            currentZoom.value,
                            currentOffsetX.value,
                            currentOffsetY.value
                        )
                        dragMode = hitTest(start, geometry)
                    },
                    onDragEnd = { dragMode = CropDragMode.None },
                    onDragCancel = { dragMode = CropDragMode.None },
                    onDrag = { change, dragAmount ->
                        val latestZoom = currentZoom.value
                        val geometry = cropGeometry(
                            viewportSize,
                            thumbnail,
                            latestZoom,
                            currentOffsetX.value,
                            currentOffsetY.value
                        )
                        when (dragMode) {
                            CropDragMode.Move -> {
                                if (geometry.imageScale <= 0f) return@detectDragGestures
                                val nextCenterX = geometry.centerX + dragAmount.x / geometry.imageScale
                                val nextCenterY = geometry.centerY + dragAmount.y / geometry.imageScale
                                val (nextOffsetX, nextOffsetY) = normalizedOffsets(thumbnail, latestZoom, nextCenterX, nextCenterY)
                                currentOnCropChanged.value(latestZoom, nextOffsetX, nextOffsetY)
                                change.consume()
                            }
                            CropDragMode.ResizeTopLeft,
                            CropDragMode.ResizeTopRight,
                            CropDragMode.ResizeBottomLeft,
                            CropDragMode.ResizeBottomRight -> {
                                resizeFromCorner(dragMode, geometry, dragAmount)
                                change.consume()
                            }
                            CropDragMode.None -> Unit
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val geometry = cropGeometry(viewportSize, thumbnail, zoom, offsetX, offsetY)
            if (geometry.cropSize <= 0f) return@Canvas
            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(geometry.imageLeft.roundToInt(), geometry.imageTop.roundToInt()),
                dstSize = IntSize(geometry.imageWidth.roundToInt().coerceAtLeast(1), geometry.imageHeight.roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.High
            )

            val cropRight = geometry.cropLeft + geometry.cropSize
            val cropBottom = geometry.cropTop + geometry.cropSize
            val scrim = Color.Black.copy(alpha = 0.48f)

            drawRect(scrim, topLeft = Offset.Zero, size = Size(size.width, geometry.cropTop.coerceAtLeast(0f)))
            drawRect(scrim, topLeft = Offset(0f, cropBottom), size = Size(size.width, (size.height - cropBottom).coerceAtLeast(0f)))
            drawRect(scrim, topLeft = Offset(0f, geometry.cropTop), size = Size(geometry.cropLeft.coerceAtLeast(0f), geometry.cropSize))
            drawRect(scrim, topLeft = Offset(cropRight, geometry.cropTop), size = Size((size.width - cropRight).coerceAtLeast(0f), geometry.cropSize))

            drawRect(
                color = Color.White,
                topLeft = Offset(geometry.cropLeft, geometry.cropTop),
                size = Size(geometry.cropSize, geometry.cropSize),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                )
            )
            val handleRadius = 14.dp.toPx()
            val handleStroke = 3.dp.toPx()
            listOf(
                Offset(geometry.cropLeft, geometry.cropTop),
                Offset(cropRight, geometry.cropTop),
                Offset(geometry.cropLeft, cropBottom),
                Offset(cropRight, cropBottom)
            ).forEach { handle ->
                drawCircle(handleColor, radius = handleRadius, center = handle)
                drawCircle(Color.White, radius = handleRadius, center = handle, style = Stroke(width = handleStroke))
            }
        }
    }
}

@Composable
private fun InstantFitToggle(
    enabled: Boolean,
    checked: Boolean,
    onToggle: () -> Unit
) {
    FilterChip(
        selected = checked,
        enabled = enabled,
        onClick = onToggle,
        label = { Text(stringResource(R.string.instant_fit_button_name)) },
        leadingIcon = {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.image_24px),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    )
}

@Composable
private fun MediaThumbnailCard(
    modifier: Modifier = Modifier,
    mediaType: MediaType,
    thumbnail: Bitmap?,
    instantPreview: Boolean,
    instantCropZoom: Float,
    instantCropOffsetX: Float,
    instantCropOffsetY: Float,
    onClick: () -> Unit,
    onClear: () -> Unit
) {
    var isInDeleteMode by remember { mutableStateOf(false) }

    fun handleOnClick() {
        if (isInDeleteMode) {
            isInDeleteMode = false
        } else {
            onClick()
        }
    }

    fun handleOnLongClick() {
        if (thumbnail != null) {
            isInDeleteMode = true
        }
    }

    fun handleOnClear() {
        onClear()
        isInDeleteMode = false
    }

    Box(
        modifier = modifier
            .aspectRatio(if (instantPreview) 1f else 9f / 16f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = ::handleOnClick,
                onLongClick = ::handleOnLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        ThumbnailContent(
            thumbnail = thumbnail,
            mediaType = mediaType,
            instantPreview = instantPreview,
            instantCropZoom = instantCropZoom,
            instantCropOffsetX = instantCropOffsetX,
            instantCropOffsetY = instantCropOffsetY
        )
        DeleteModeOverlay(isInDeleteMode, ::handleOnClear)
    }
}

@Composable
private fun ThumbnailContent(
    thumbnail: Bitmap?,
    mediaType: MediaType,
    instantPreview: Boolean,
    instantCropZoom: Float,
    instantCropOffsetX: Float,
    instantCropOffsetY: Float
) {
    if (thumbnail != null) {
        if (instantPreview && mediaType == MediaType.IMAGE) {
            InstantCropPreview(
                thumbnail = thumbnail,
                zoom = instantCropZoom,
                offsetX = instantCropOffsetX,
                offsetY = instantCropOffsetY
            )
        } else {
            Image (
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    } else {
        val iconResource = if (mediaType == MediaType.VIDEO) {
            R.drawable.video_file_24px
        } else {
            R.drawable.image_24px
        }
        Image(
            imageVector = ImageVector.vectorResource(iconResource),
            contentDescription = null,
            modifier = Modifier.scale(1.5f)
        )
    }
}

@Composable
private fun InstantCropPreview(
    thumbnail: Bitmap,
    zoom: Float,
    offsetX: Float,
    offsetY: Float
) {
    val imageBitmap = remember(thumbnail) { thumbnail.asImageBitmap() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val sourceSize = sourceSizeFor(thumbnail, zoom)
        val (centerX, centerY) = cropCenter(thumbnail, zoom, offsetX, offsetY)
        val sourceLeft = (centerX - sourceSize / 2f).coerceIn(0f, thumbnail.width - sourceSize)
        val sourceTop = (centerY - sourceSize / 2f).coerceIn(0f, thumbnail.height - sourceSize)
        drawImage(
            image = imageBitmap,
            srcOffset = IntOffset(sourceLeft.roundToInt(), sourceTop.roundToInt()),
            srcSize = IntSize(sourceSize.roundToInt().coerceAtLeast(1), sourceSize.roundToInt().coerceAtLeast(1)),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.High
        )
        drawRect(Color.Black.copy(alpha = 0.18f), style = Stroke(width = 1.dp.toPx()))
    }
}

@Composable
private fun DeleteModeOverlay(visible: Boolean, onClear: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.6f),
                        Color.Transparent,
                        Color.Transparent
                    )
                )
            )
        ){
            IconButton(
                onClick = onClear,
                modifier = Modifier.align(Alignment.Center).padding(8.dp).size(28.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.close_24px),
                    contentDescription = "清除缩略图",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun RadioButtonRow(selected: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)

        )
    }
}

@Composable
private fun ModuleSwitch(
    text: String,
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.developer_board_24px),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
fun OnLifecycleEvent(onEvent: (event: Lifecycle.Event) -> Unit) {
    val eventHandler by rememberUpdatedState(onEvent)
    val lifecycleOwner by rememberUpdatedState(LocalLifecycleOwner.current)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            eventHandler(event)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
