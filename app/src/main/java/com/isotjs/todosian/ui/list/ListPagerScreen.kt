package com.isotjs.todosian.ui.list

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.model.TodoList
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.ui.components.TodosianEmptyState
import com.isotjs.todosian.ui.components.TodosianLoadingIndicator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch

data class ListPagerUiState(
    val isLoading: Boolean = true,
    /** The lists of the containing folder, in pager order (A–Z). */
    val pages: List<TodoList> = emptyList(),
)

/**
 * Swipeable pager over a folder's lists. Resolves the folder containing [listUri] itself, so
 * widget intents can route straight into a list without knowing its folder. Swiping left/right
 * moves between the folder's lists; each page is a full [ListPage].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListPagerScreen(
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    listUri: Uri,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    autoOpenAddTodo: Boolean = false,
) {
    val pagerViewModel: ListPagerViewModel = viewModel(
        factory = ListPagerViewModelFactory(fileRepository, listUri),
    )
    val uiState by pagerViewModel.uiState.collectAsStateWithLifecycle()

    val pages = uiState.pages
    val pagerState = rememberPagerState(pageCount = { pages.size })

    // Track which list the user is currently looking at so re-scans (renames, external changes)
    // can keep the pager on the same list instead of jumping back to the entry page.
    var currentListUri by remember { mutableStateOf(listUri) }
    LaunchedEffect(pagerState.currentPage, pages) {
        val page = pages.getOrNull(pagerState.currentPage)
        if (page != null) currentListUri = page.uri
    }

    // First time the pages arrive, land on the requested list.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(pages, landed) {
        if (!landed && pages.isNotEmpty()) {
            val index = pages.indexOfFirst { it.uri == listUri }
            if (index >= 0) pagerState.scrollToPage(index)
            landed = true
        }
    }

    // If the current list disappears from its folder (moved elsewhere or deleted externally),
    // leave this screen rather than showing a stale page.
    LaunchedEffect(pages) {
        if (!uiState.isLoading && pages.isNotEmpty() && pages.none { it.uri == currentListUri }) {
            onBack()
        }
    }

    if (uiState.isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TodosianLoadingIndicator()
        }
        return
    }

    if (pages.isEmpty()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = "") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_navigate_back),
                            )
                        }
                    },
                )
            },
            modifier = modifier.fillMaxSize(),
        ) { padding ->
            TodosianEmptyState(
                icon = Icons.Outlined.Checklist,
                title = stringResource(R.string.list_unavailable_title),
                subtitle = stringResource(R.string.list_unavailable_subtitle),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
        return
    }

    HorizontalPager(
        state = pagerState,
        key = { pageIndex -> pages.getOrNull(pageIndex)?.uri ?: pageIndex },
        modifier = modifier.fillMaxSize(),
    ) { pageIndex ->
        val page = pages.getOrNull(pageIndex) ?: return@HorizontalPager
        ListPage(
            fileRepository = fileRepository,
            appSettingsRepository = appSettingsRepository,
            listUri = page.uri,
            onBack = onBack,
            autoOpenAddTodo = autoOpenAddTodo && page.uri == listUri,
            listPosition = pageIndex + 1,
            listCount = pages.size,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

class ListPagerViewModel(
    private val fileRepository: FileRepository,
    private val listUri: Uri,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListPagerUiState())
    val uiState: StateFlow<ListPagerUiState> = _uiState.asStateFlow()

    init {
        observeVaultChanges()
        refresh()
    }

    private fun observeVaultChanges() {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            val rootUri = fileRepository.getFolderUri() ?: return@launch
            fileRepository.observeMarkdownFilesChanges(rootUri)
                .debounce(250)
                .catch { /* best-effort */ }
                .collectLatest {
                    refresh()
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val content = fileRepository.getVaultContent().getOrNull()
            val containingFolder = content?.folders?.firstOrNull { folder ->
                folder.lists.any { it.uri == listUri }
            }
            val pages = when {
                containingFolder != null -> containingFolder.lists
                // Not inside any folder: either an unfiled (root-level) list or a deleted one.
                content?.unfiledLists?.any { it.uri == listUri } == true -> content.unfiledLists
                else -> emptyList()
            }
            _uiState.value = ListPagerUiState(isLoading = false, pages = pages)
        }
    }
}

private class ListPagerViewModelFactory(
    private val fileRepository: FileRepository,
    private val listUri: Uri,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ListPagerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ListPagerViewModel(fileRepository, listUri) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
