package dev.kobbler.app.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.rememberZoomState
import dev.kobbler.app.ui.components.zoomable
import dev.kobbler.app.ui.components.zoomed
import dev.kobbler.app.ui.safeBottomPadding
import dev.kobbler.app.ui.safeTopPadding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val PAGE_PREFETCH = 2
private const val PAGE_CACHE_SIZE = 2 * PAGE_PREFETCH + 3
private const val THUMB_CACHE_SIZE = 24
private const val FALLBACK_PAGE_ASPECT = 2f / 3f

@Composable
fun ComicReader(
    book: Book,
    api: KobblerApi,
    onBack: () -> Unit,
) {
    var source by remember { mutableStateOf<ComicSource?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(book.id) {
        loading = true
        error = null
        val result = try {
            loadComic(book, api)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ComicLoadResult.Failed(e.message ?: "Failed to load comic")
        }
        when (result) {
            is ComicLoadResult.Ready -> source = result.source
            is ComicLoadResult.Failed -> error = result.message
        }
        loading = false
    }

    // The archive stays open for the whole reading session — pages are inflated from it
    // on demand rather than up front — so it must be closed on the way out.
    DisposableEffect(source) {
        val open = source
        onDispose { open?.close() }
    }

    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val dimens = KobappTheme.dimens

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        val current = source
        when {
            loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = colors.textHi,
                )
            }
            error != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(dimens.s6),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error!!, color = colors.textHi, style = type.body)
                    Spacer(Modifier.height(dimens.s4))
                    KobButton(text = "Go Back", onClick = onBack, style = KobButtonStyle.Default)
                }
            }
            current != null -> ComicPager(book = book, source = current, onBack = onBack)
        }
    }
}

@Composable
private fun ComicPager(
    book: Book,
    source: ComicSource,
    onBack: () -> Unit,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val dimens = KobappTheme.dimens
    val accent = KobappTheme.accent

    val listState = rememberLazyListState()
    val thumbState = rememberLazyListState()

    val pageCache = remember(source) { ComicPageCache(source, PAGE_CACHE_SIZE) }
    val thumbCache = remember(source) { ComicPageCache(source, THUMB_CACHE_SIZE) }
    // Learned from the first decoded page and then left alone: comics are uniform, and a
    // spread that disagrees must not be allowed to relayout the whole list.
    var pageAspect by remember(source) { mutableFloatStateOf(0f) }

    val zoom = rememberZoomState()
    var chromeVisible by remember { mutableStateOf(true) }
    var activityTick by remember { mutableIntStateOf(0) }

    // Collected rather than keyed: a pinch bumps the tick every frame, and a key read
    // here would recompose the whole pager for each one.
    LaunchedEffect(Unit) {
        snapshotFlow { activityTick to chromeVisible }.collectLatest { (_, visible) ->
            if (visible) {
                delay(3000)
                chromeVisible = false
            }
        }
    }

    val currentPage by remember(source) {
        derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, (source.pageCount - 1).coerceAtLeast(0)) }
    }

    val scope = rememberCoroutineScope()

    LaunchedEffect(pageCache, currentPage) {
        pageCache.load(((currentPage - PAGE_PREFETCH)..(currentPage + PAGE_PREFETCH)).toList(), currentPage)
    }
    LaunchedEffect(thumbCache, thumbState) {
        snapshotFlow { thumbState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { visible -> thumbCache.load(visible) }
    }

    LaunchedEffect(currentPage) {
        thumbState.animateScrollToItem(currentPage.coerceAtLeast(0))
    }

    Box(
        Modifier
            .fillMaxSize()
            .zoomable(
                zoom,
                onTap = {
                    chromeVisible = !chromeVisible
                    activityTick++
                },
                onChange = { activityTick++ },
            )
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .zoomed(zoom),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(source.pageCount, key = { it }) { index ->
                val bytes = pageCache[index]
                if (bytes == null) {
                    // Placeholders take a page-shaped slot so streaming does not make the
                    // list jump under the reader's thumb.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(if (pageAspect > 0f) pageAspect else FALLBACK_PAGE_ASPECT)
                            .background(colors.elev1)
                    )
                } else {
                    val context = LocalPlatformContext.current
                    val request = remember(bytes) {
                        ImageRequest.Builder(context).data(bytes).memoryCacheKey("comic:${book.id}:$index").build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                        onSuccess = { state ->
                            if (pageAspect <= 0f) {
                                val size = state.painter.intrinsicSize
                                if (size.width > 0f && size.height > 0f) pageAspect = size.width / size.height
                            }
                        },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopChrome(
                title = book.title,
                page = currentPage + 1,
                total = source.pageCount,
                onBack = onBack,
                bgColor = colors.bg.copy(alpha = 0.8f),
                titleColor = colors.textHi,
                counterColor = colors.textMd,
                counterStyle = type.bodySm,
                titleStyle = type.h3,
                gutter = dimens.s4,
            )
        }

        AnimatedVisibility(
            visible = chromeVisible && source.pageCount > 1,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ThumbnailStrip(
                pageCount = source.pageCount,
                thumbnail = { thumbCache[it] },
                cacheKey = { "comic:${book.id}:$it:thumb" },
                currentPage = currentPage,
                state = thumbState,
                onSelect = { idx ->
                    scope.launch { listState.animateScrollToItem(idx) }
                    activityTick++
                },
                bgColor = colors.bg.copy(alpha = 0.8f),
                borderColor = accent,
                idleBorder = colors.lineSoft,
                gutter = dimens.s4,
                gap = dimens.s2,
            )
        }
    }
}

@Composable
private fun TopChrome(
    title: String,
    page: Int,
    total: Int,
    onBack: () -> Unit,
    bgColor: Color,
    titleColor: Color,
    counterColor: Color,
    counterStyle: TextStyle,
    titleStyle: TextStyle,
    gutter: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .safeTopPadding()
            .padding(horizontal = gutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Transparent)
                .pointerInput(onBack) { detectTapGestures(onTap = { onBack() }) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = titleColor)
        }
        Spacer(Modifier.width(gutter))
        Text(
            text = title,
            style = titleStyle,
            color = titleColor,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$page / $total",
            style = counterStyle,
            color = counterColor,
        )
    }
}

@Composable
private fun ThumbnailStrip(
    pageCount: Int,
    thumbnail: (Int) -> ByteArray?,
    cacheKey: (Int) -> String,
    currentPage: Int,
    state: LazyListState,
    onSelect: (Int) -> Unit,
    bgColor: Color,
    borderColor: Color,
    idleBorder: Color,
    gutter: Dp,
    gap: Dp,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(bgColor)
            .safeBottomPadding()
            .padding(horizontal = gutter, vertical = 10.dp),
    ) {
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            items(pageCount, key = { it }) { index ->
                val active = index == currentPage
                Box(
                    Modifier
                        .height(64.dp)
                        .width(48.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) borderColor else idleBorder,
                            shape = RoundedCornerShape(4.dp),
                        )
                        .pointerInput(index) { detectTapGestures(onTap = { onSelect(index) }) },
                ) {
                    val bytes = thumbnail(index)
                    if (bytes != null) {
                        val context = LocalPlatformContext.current
                        val request = remember(bytes) {
                            ImageRequest.Builder(context)
                                .data(bytes)
                                .memoryCacheKey(cacheKey(index))
                                .size(ThumbDecodeWidthPx, ThumbDecodeHeightPx)
                                .build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

// 48x64dp thumbnails at up to 2x density; the pages behind them are full scans.
private const val ThumbDecodeWidthPx = 96
private const val ThumbDecodeHeightPx = 128
