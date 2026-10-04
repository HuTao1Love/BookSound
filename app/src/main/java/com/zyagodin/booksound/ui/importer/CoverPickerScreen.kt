package com.zyagodin.booksound.ui.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zyagodin.booksound.AppContainer
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.organize.CoverCandidate
import com.zyagodin.booksound.core.organize.CoverOrigin
import com.zyagodin.booksound.cover.CoverImages
import com.zyagodin.booksound.cover.CoverSearchRepository
import com.zyagodin.booksound.cover.CoverSearchResult
import com.zyagodin.booksound.cover.OnlineCover
import com.zyagodin.booksound.cover.SquareCrop
import com.zyagodin.booksound.importer.OnlineCoverState
import com.zyagodin.booksound.importer.SelectedCover
import com.zyagodin.booksound.ui.AppNavigator
import com.zyagodin.booksound.ui.components.CoverImage
import com.zyagodin.booksound.ui.components.ErrorState
import com.zyagodin.booksound.ui.components.LoadingDots
import com.zyagodin.booksound.ui.components.QuietButton
import com.zyagodin.booksound.ui.components.SectionHeader
import com.zyagodin.booksound.ui.components.TonalButton
import com.zyagodin.booksound.ui.navigation.appViewModel
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CoverPickerViewModel(private val container: AppContainer, sessionId: String) : ViewModel() {
    private val session = container.importSessions[sessionId]
    val available: Boolean get() = session != null
    /** Local candidates (embedded / folder images) with preview files for display. */
    val candidates: StateFlow<List<Pair<CoverCandidate, java.io.File>>> = (session?.coverCandidates ?: MutableStateFlow(emptyList()))
        .map { list -> list.map { it to container.covers.draftFile(sessionId, it.picture) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val online: StateFlow<OnlineCoverState> = session?.online ?: MutableStateFlow(OnlineCoverState.Idle)
    val selected: StateFlow<SelectedCover?> = session?.cover ?: MutableStateFlow(null)
    val downloading = MutableStateFlow<String?>(null)

    /** "Series Title Author": a volume title alone often finds nothing useful. */
    val initialQuery: String = session?.form?.value
        ?.let { listOf(CoverSearchRepository.withSeries(it.title, it.series), it.author).filter { s -> s.isNotBlank() }.joinToString(" ") }
        .orEmpty()

    fun search(query: String) {
        val s = session ?: return
        if (query.isBlank()) return
        s.online.value = OnlineCoverState.Loading
        // The suggested "series title author" query is searched in its parts (also by title
        // alone); a query the user typed is searched as typed.
        val form = s.form.value
        val suggested = query.trim() == initialQuery.trim()
        viewModelScope.launch {
            val r = if (suggested && form != null) {
                container.coverSearch.search(form.title, form.author.ifBlank { null }, form.series)
            } else {
                container.coverSearch.search(query, null)
            }
            s.online.value = when (r) {
                is CoverSearchResult.Found -> OnlineCoverState.Results(query, r.covers)
                CoverSearchResult.Offline -> OnlineCoverState.Offline
                CoverSearchResult.Failed -> OnlineCoverState.Failed
            }
        }
    }

    fun chooseCandidate(candidate: CoverCandidate) {
        val s = session ?: return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { container.covers.draftFile(s.id, candidate.picture) }
            s.cover.value = SelectedCover(candidate.picture, file, candidate)
        }
    }

    /** Downloads an online result; the screen crops it to a square if needed, then calls [useCover]. */
    fun downloadOnline(cover: OnlineCover, onResult: (EmbeddedPicture?) -> Unit) {
        viewModelScope.launch {
            downloading.value = cover.fullUrl
            val picture = container.coverSearch.download(cover)
            downloading.value = null
            onResult(picture)
        }
    }

    /**
     * Reads a picture from the gallery. Square pictures are normalized right away; others are
     * returned as they are so the crop works on the full resolution.
     */
    fun readFromDevice(uri: Uri, onResult: (EmbeddedPicture?) -> Unit) {
        viewModelScope.launch {
            val picture = withContext(Dispatchers.IO) {
                val bytes = container.documents.readBytes(uri, 25 * 1024 * 1024) ?: return@withContext null
                val (w, h) = CoverImages.dimensions(bytes) ?: return@withContext null
                if (SquareCrop.isSquare(w, h)) CoverImages.normalize(bytes)
                else EmbeddedPicture(bytes, EmbeddedPicture.sniffMimeType(bytes) ?: "image/jpeg")
            }
            onResult(picture)
        }
    }

    fun useCover(picture: EmbeddedPicture, origin: CoverOrigin, label: String) {
        val s = session ?: return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { container.covers.draftFile(s.id, picture) }
            s.cover.value = SelectedCover(picture, file, CoverCandidate(picture, origin, label))
        }
    }

    fun remove() {
        session?.cover?.value = null
    }
}

@Composable
fun CoverPickerScreen(sessionId: String, navigator: AppNavigator) {
    val vm = appViewModel(key = "cover-$sessionId") { CoverPickerViewModel(it, sessionId) }
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf(vm.initialQuery) }
    val focus = LocalFocusManager.current

    var cropping by remember { mutableStateOf<PendingCover?>(null) }

    /** Uses the picture, asking for a square crop first when it isn't square. */
    fun offer(picture: EmbeddedPicture, origin: CoverOrigin, label: String) {
        if (needsCrop(picture)) {
            cropping = PendingCover(picture, origin, label)
        } else {
            vm.useCover(picture, origin, label)
            navigator.back()
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            vm.readFromDevice(uri) { picture ->
                if (picture != null) offer(picture, CoverOrigin.USER, context.getString(R.string.cover_from_device))
                else scope.launch { snackbar.showSnackbar(context.getString(R.string.cover_invalid_image)) }
            }
        }
    }
    cropping?.let { pending ->
        SquareCropDialog(
            picture = pending.picture,
            onCropped = { cropped ->
                cropping = null
                vm.useCover(cropped, pending.origin, pending.label)
                navigator.back()
            },
            onDismiss = { cropping = null },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                Text(stringResource(R.string.cover_picker_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = Spacing.sm))
            }
        },
    ) { padding ->
        if (!vm.available) {
            ErrorState(Icons.Rounded.ErrorOutline, stringResource(R.string.editor_expired_title), stringResource(R.string.editor_expired_message), Modifier.padding(padding))
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            contentPadding = PaddingValues(
                start = Spacing.lg, end = Spacing.lg, top = Spacing.sm,
                bottom = Spacing.xl + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            fullWidth("current") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(112.dp)) {
                        CoverImage(selected?.file, "", Modifier.fillMaxSize())
                        if (selected == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.HideImage, null, tint = Color.White.copy(alpha = 0.8f))
                            }
                        }
                    }
                    Spacer(Modifier.width(Spacing.lg))
                    Column {
                        Text(stringResource(if (selected == null) R.string.cover_none else R.string.cover_current), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(Spacing.sm))
                        TonalButton(
                            stringResource(R.string.action_from_gallery),
                            { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            icon = Icons.Rounded.PhotoLibrary,
                        )
                        if (selected != null) QuietButton(stringResource(R.string.action_remove_cover), { vm.remove(); navigator.back() })
                    }
                }
            }
            if (candidates.isNotEmpty()) {
                fullWidth("files-header") { SectionHeader(stringResource(R.string.cover_section_files), Modifier.padding(top = Spacing.md)) }
                items(candidates, key = { "c-" + it.second.name }) { (candidate, file) ->
                    val isSelected = selected?.picture == candidate.picture
                    CoverTile(
                        model = file,
                        label = candidate.label,
                        selected = isSelected,
                        busy = false,
                        onClick = {
                            vm.chooseCandidate(candidate)
                            navigator.back()
                        },
                    )
                }
            }
            fullWidth("online-header") {
                Column(Modifier.padding(top = Spacing.md)) {
                    SectionHeader(stringResource(R.string.cover_section_online))
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.cover_search_hint)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        singleLine = true,
                        shape = Radii.pill,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focus.clearFocus()
                            vm.search(query)
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            when (val s = online) {
                OnlineCoverState.Idle -> fullWidth("online-idle") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TonalButton(stringResource(R.string.action_search_online), { vm.search(query) }, icon = Icons.Rounded.Search)
                    }
                }
                OnlineCoverState.Loading -> fullWidth("online-loading") {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { LoadingDots() }
                }
                OnlineCoverState.Offline -> fullWidth("online-offline") { InlineMessage(Icons.Rounded.WifiOff, stringResource(R.string.cover_offline)) }
                OnlineCoverState.Failed -> fullWidth("online-failed") { InlineMessage(Icons.Rounded.ErrorOutline, stringResource(R.string.cover_search_failed)) }
                is OnlineCoverState.Results -> if (s.covers.isEmpty()) {
                    fullWidth("online-empty") { InlineMessage(Icons.Rounded.Search, stringResource(R.string.cover_no_online_results)) }
                } else {
                    items(s.covers, key = { "o-" + it.fullUrl }) { cover ->
                        CoverTile(
                            model = cover.thumbnailUrl,
                            label = cover.source,
                            selected = false,
                            busy = downloading == cover.fullUrl,
                            onClick = {
                                if (downloading == null) {
                                    vm.downloadOnline(cover) { picture ->
                                        if (picture != null) offer(picture, CoverOrigin.ONLINE, cover.source)
                                        else scope.launch { snackbar.showSnackbar(context.getString(R.string.cover_download_failed)) }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A picture waiting for the user to crop it to a square. */
private class PendingCover(val picture: EmbeddedPicture, val origin: CoverOrigin, val label: String)

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun CoverTile(model: Any?, label: String, selected: Boolean, busy: Boolean, onClick: () -> Unit) {
    Column {
        Surface(
            onClick = onClick,
            shape = Radii.cover,
            border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box {
                CoverImage(model, label, Modifier.fillMaxWidth())
                if (selected) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
                    }
                }
                if (busy) {
                    Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f), modifier = Modifier.matchParentSize()) {
                        Box(contentAlignment = Alignment.Center) { LoadingDots(color = Color.White) }
                    }
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xs))
    }
}

@Composable
private fun InlineMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.md))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
