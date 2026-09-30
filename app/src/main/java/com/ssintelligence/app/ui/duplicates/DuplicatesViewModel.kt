package com.ssintelligence.app.ui.duplicates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.DuplicateGroup
import com.ssintelligence.app.domain.usecase.ObserveDuplicateGroupsUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DuplicatesViewModel(
    useCase: ObserveDuplicateGroupsUseCase,
) : ViewModel() {

    val groups: StateFlow<List<DuplicateGroup>> = useCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DuplicatesViewModel(
            ObserveDuplicateGroupsUseCase(locator.screenshotRepository),
        ) as T
    }
}
