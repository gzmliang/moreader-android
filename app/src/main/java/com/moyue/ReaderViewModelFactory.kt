package com.moyue.app.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.moyue.app.data.BookRepository

class ReaderViewModelFactory(
    private val repository: BookRepository,
    private val application: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ReaderViewModel(application, repository) as T
    }
}
