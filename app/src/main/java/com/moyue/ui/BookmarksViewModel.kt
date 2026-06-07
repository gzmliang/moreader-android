package com.moyue.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moyue.app.data.BookRepository
import com.moyue.app.data.models.Bookmark
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BookmarksViewModel(
    private val repository: BookRepository
) : ViewModel() {

    val bookmarks: StateFlow<List<Bookmark>> = repository.getAllBookmarks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun deleteBookmark(id: Long) {
        viewModelScope.launch {
            repository.deleteBookmark(id)
        }
    }
}

class BookmarksViewModelFactory(
    private val repository: BookRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BookmarksViewModel::class.java)) {
            return BookmarksViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
