package com.isotjs.todosian

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import com.isotjs.todosian.ui.NavSeed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.isotjs.todosian.ui.theme.TodosianTheme
import com.isotjs.todosian.data.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Request to open the add-task flow. [categoryUri] non-null routes straight into that list;
 * [folderUri] non-null scopes the picker to that folder's lists; both null opens the full
 * vault picker.
 */
data class AddTaskRequest(val tick: Long, val categoryUri: String?, val folderUri: String? = null)

class MainActivity : ComponentActivity() {

    private val addTaskSignal = MutableStateFlow(AddTaskRequest(0L, null))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        val preferencesManager = (application as TodosianApplication).preferencesManager
        val fileRepository = (application as TodosianApplication).fileRepository
        val appSettingsRepository = (application as TodosianApplication).appSettingsRepository
        val initialAddTaskRequest = addTaskSignal.value
        setContent {
            val settings = appSettingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = com.isotjs.todosian.data.settings.AppSettings(),
            ).value

            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            TodosianTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColorEnabled,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TodosianApp(
                        fileRepository = fileRepository,
                        appSettingsRepository = appSettingsRepository,
                        preferencesManager = preferencesManager,
                        addTaskSignal = addTaskSignal,
                        initialAddTaskRequest = initialAddTaskRequest,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val wantsGlobalAdd = intent.getBooleanExtra(EXTRA_OPEN_ADD_TASK, false)
        val fileRepository = (application as TodosianApplication).fileRepository
        val categoryUri = intent.getStringExtra(EXTRA_OPEN_ADD_TASK_CATEGORY)
            ?.takeIf { it.isNotBlank() }
            ?.toUri()
            ?.takeIf { fileRepository.isCategoryUri(it) }
            ?.toString()
        // The folder extra only scopes the picker's display; the picked list is validated on
        // its own path, so no folder-level SAF check is needed here.
        val folderUri = intent.getStringExtra(EXTRA_OPEN_ADD_TASK_FOLDER)
            ?.takeIf { it.isNotBlank() }
        if (folderUri != null) {
            NavSeed.seedAddTaskFolder(folderUri.toUri())
        }
        if (wantsGlobalAdd || categoryUri != null || folderUri != null) {
            addTaskSignal.value = AddTaskRequest(addTaskSignal.value.tick + 1L, categoryUri, folderUri)
            intent.removeExtra(EXTRA_OPEN_ADD_TASK)
            intent.removeExtra(EXTRA_OPEN_ADD_TASK_CATEGORY)
            intent.removeExtra(EXTRA_OPEN_ADD_TASK_FOLDER)
        }
    }

    companion object {
        const val EXTRA_OPEN_ADD_TASK = "open_add_task"
        const val EXTRA_OPEN_ADD_TASK_CATEGORY = "open_add_task_category"
        const val EXTRA_OPEN_ADD_TASK_FOLDER = "open_add_task_folder"
    }
}
