package com.ssintelligence.app.ui.duplicates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.model.DuplicateGroup
import com.ssintelligence.app.domain.usecase.ObserveDuplicateGroupsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DuplicatesViewModel(
    useCase: ObserveDuplicateGroupsUseCase,
    private val locator: ServiceLocator,
) : ViewModel() {

    val groups: StateFlow<List<DuplicateGroup>> = useCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Near-duplicate groups, loaded once: perceptual-hash clustering over the
     * library does not change between frames, and recomputing it per
     * composition would be waste.
     */
    private val _nearGroups = MutableStateFlow(
        emptyList<com.ssintelligence.app.domain.repository.NearDuplicateGroup>(),
    )
    val nearGroups: StateFlow<List<com.ssintelligence.app.domain.repository.NearDuplicateGroup>> =
        _nearGroups

    private val _nearShots = MutableStateFlow(
        emptyMap<Long, List<com.ssintelligence.app.domain.model.Screenshot>>(),
    )
    val nearShots: StateFlow<Map<Long, List<com.ssintelligence.app.domain.model.Screenshot>>> =
        _nearShots

    init {
        viewModelScope.launch {
            val near = runCatching {
                com.ssintelligence.app.domain.usecase.ObserveNearDuplicatesUseCase(
                    locator.screenshotRepository,
                )()
            }.getOrDefault(emptyList())
            _nearGroups.value = near
            _nearShots.value = near.associate { group ->
                group.coverId to group.memberIds.mapNotNull { locator.screenshotRepository.getById(it) }
            }
        }
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DuplicatesViewModel(
            ObserveDuplicateGroupsUseCase(locator.screenshotRepository),
            locator,
        ) as T
    }
}
