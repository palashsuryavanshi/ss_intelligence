package com.ssintelligence.app.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.assistant.AssistantContext
import com.ssintelligence.app.assistant.AssistantResponse
import com.ssintelligence.app.domain.usecase.AskAssistantUseCase
import com.ssintelligence.app.domain.usecase.AssistantSuggestionsUseCase
import com.ssintelligence.app.domain.usecase.StartAssistantConversationUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Assistant screen state.
 *
 * The conversation is a list of turns; the context object is what makes "it"
 * and "that" resolve across turns. Everything the screen renders comes from a
 * validated [AssistantResponse] — the ViewModel never assembles an answer
 * itself.
 */
class AssistantViewModel(
    private val ask: AskAssistantUseCase,
    private val startConversation: StartAssistantConversationUseCase,
    suggestions: AssistantSuggestionsUseCase,
) : ViewModel() {

    data class UiState(
        val turns: List<Turn> = emptyList(),
        val suggestions: List<AssistantSuggestionsUseCase.Suggestion> = emptyList(),
        val thinking: Boolean = false,
        val error: String? = null,
        val conversationId: Long? = null,
    ) {
        data class Turn(val userText: String, val response: AssistantResponse?)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var context: AssistantContext = AssistantContext.Empty

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(suggestions = suggestions())
        }
    }

    /** Sends a question. Retrieval runs before any answer text is produced. */
    fun ask(query: String) {
        val text = query.trim()
        if (text.isEmpty() || _state.value.thinking) return
        _state.value = _state.value.copy(
            thinking = true,
            error = null,
            turns = _state.value.turns + UiState.Turn(text, null),
        )
        viewModelScope.launch {
            try {
                val conversationId = _state.value.conversationId
                    ?: startConversation(text).also { _state.value = _state.value.copy(conversationId = it) }
                val result = ask(text, conversationId, context)
                context = result.context
                _state.value = _state.value.copy(
                    thinking = false,
                    turns = _state.value.turns.dropLast(1) + UiState.Turn(text, result.response),
                )
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    thinking = false,
                    error = "I could not answer that. The assistant works fully offline — please try again.",
                )
            }
        }
    }

    /** Starts a fresh conversation, discarding the on-screen one. */
    fun newConversation() {
        context = AssistantContext.Empty
        _state.value = UiState(conversationId = null)
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AssistantViewModel(
            ask = AskAssistantUseCase(locator.assistant, locator.assistantRepository),
            startConversation = StartAssistantConversationUseCase(locator.assistantRepository),
            suggestions = AssistantSuggestionsUseCase(locator.screenshotRepository),
        ) as T
    }
}
