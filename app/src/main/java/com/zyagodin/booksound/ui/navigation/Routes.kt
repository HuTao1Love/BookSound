package com.zyagodin.booksound.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import com.zyagodin.booksound.AppContainer
import com.zyagodin.booksound.BookSoundApp
import kotlinx.serialization.Serializable

@Serializable data object LibraryKey : NavKey
@Serializable data class BookKey(val bookId: String) : NavKey
@Serializable data object PlayerKey : NavKey
@Serializable data class ImportEditorKey(val sessionId: String) : NavKey
@Serializable data class CoverPickerKey(val sessionId: String) : NavKey
@Serializable data object ImportsKey : NavKey
@Serializable data object SettingsKey : NavKey
@Serializable data object RemovedBooksKey : NavKey

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as BookSoundApp).container

/** ViewModel scoped to the current navigation entry, built from the app container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
