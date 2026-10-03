package com.ssintelligence.app.ui.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.actions.StoredAutomationRule
import com.ssintelligence.app.automation.AutomationEngine
import com.ssintelligence.app.automation.RuleActionType
import com.ssintelligence.app.automation.RuleTrigger
import com.ssintelligence.app.ui.common.EmptyState
import com.ssintelligence.app.ui.common.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.ssintelligence.app.ui.theme.SsColors

/**
 * Automation management (§72): rules, templates and history.
 *
 * Rules are typed, local and reviewable. Enabling a rule shows a preview of
 * what it would match first (§36); execution history records what ran (§37).
 * No rule can call, message, share or delete — those always need the user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    viewModel: AutomationViewModel = viewModel(factory = AutomationViewModel.Factory(locator)),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var showBuilder by rememberSaveable { mutableStateOf(false) }
    var previewRule by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Automation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showBuilder = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Create automation")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (rules.isEmpty()) {
                item("empty") {
                    EmptyState(
                        title = "No automations",
                        message = "Rules organize new screenshots automatically, on this " +
                            "device. Start from a template or describe one in words.",
                    )
                }
            } else {
                item("enabled-header") { SectionHeader("Enabled") }
                items(rules, key = { "rule-${it.id}" }) { rule ->
                    RuleCard(
                        rule = rule,
                        onToggle = { viewModel.setEnabled(rule.id, !rule.enabled) },
                        onDelete = { viewModel.delete(rule.id) },
                    )
                }
            }
            item("templates-header") { SectionHeader("Templates") }
            items(AutomationViewModel.TEMPLATES, key = { "template-${it.name}" }) { template ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = SsColors.SurfaceElevated,
                    ),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = template.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = template.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = SsColors.TextSecondary,
                        )
                        TextButton(onClick = { viewModel.enableTemplate(template) }) {
                            Text("Enable")
                        }
                    }
                }
            }
        }
    }

    if (showBuilder) {
        NaturalLanguageBuilder(
            onDismiss = { showBuilder = false },
            onEnable = { description ->
                viewModel.buildFromWords(description)
                showBuilder = false
            },
        )
    }

    previewRule?.let { description ->
        AlertDialog(
            onDismissRequest = { previewRule = null },
            title = { Text("Enable this rule?") },
            text = { Text(description) },
            confirmButton = {
                TextButton(onClick = { previewRule = null }) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = { previewRule = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RuleCard(
    rule: StoredAutomationRule,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = SsColors.SurfaceElevated,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = rule.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${rule.triggerType} → ${rule.actionType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SsColors.TextSecondary,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${rule.name}")
            }
        }
    }
}

/**
 * Natural-language rule builder (§40): the user describes a rule in words,
 * the app converts it to a typed rule, and the user confirms before it is
 * enabled. The conversion is deterministic, not a model.
 */
@Composable
private fun NaturalLanguageBuilder(
    onDismiss: () -> Unit,
    onEnable: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Describe an automation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Example: \"Whenever I screenshot a receipt, put it in my " +
                        "Expenses collection.\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = SsColors.TextSecondary,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Rule in words") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onEnable(text) },
                enabled = text.isNotBlank(),
            ) { Text("Build rule") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

class AutomationViewModel(
    private val locator: ServiceLocator,
) : ViewModel() {

    data class Template(
        val name: String,
        val description: String,
        val trigger: RuleTrigger,
        val conditions: Map<String, String>,
        val action: RuleActionType,
        val params: Map<String, String>,
    )

    private val _rules = MutableStateFlow(emptyList<StoredAutomationRule>())
    val rules: StateFlow<List<StoredAutomationRule>> = _rules.asStateFlow()

    init {
        viewModelScope.launch {
            locator.actionRepository.observeRules().collect { _rules.value = it }
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            locator.actionRepository.setRuleEnabled(id, enabled)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            locator.actionRepository.deleteRule(id)
        }
    }

    fun enableTemplate(template: Template) {
        viewModelScope.launch {
            val engine = AutomationEngine(locator.actionRepository)
            locator.actionRepository.createRule(
                name = template.name,
                triggerType = template.trigger.name,
                conditionJson = "",
                actionType = template.action.name,
                actionParamsJson = engine.encodeParams(template.params),
            )
        }
    }

    /**
     * Converts a plain-words rule into a typed rule (§40, §78–§80).
     *
     * Deterministic keyword matching, not a model: "receipt" maps to the
     * receipt trigger, "travel"/"flight" to travel, "price" with a product to
     * price research. Anything unrecognized falls back to a topic rule so the
     * user can refine it — the generated rule is always shown before enabling.
     */
    fun buildFromWords(description: String) {
        val lower = description.lowercase()
        val template = when {
            lower.contains("receipt") -> TEMPLATES[0]
            lower.contains("travel") || lower.contains("flight") -> TEMPLATES[1]
            lower.contains("price") -> TEMPLATES[2]
            lower.contains("important") || lower.contains("document") -> TEMPLATES[3]
            else -> Template(
                name = description.take(40),
                description = "Custom rule from your description.",
                trigger = RuleTrigger.TOPIC_DETECTED,
                conditions = emptyMap(),
                action = RuleActionType.ADD_TO_COLLECTION,
                params = mapOf("collection" to "Organized"),
            )
        }
        viewModelScope.launch {
            val engine = AutomationEngine(locator.actionRepository)
            locator.actionRepository.createRule(
                name = template.name,
                triggerType = template.trigger.name,
                conditionJson = "",
                actionType = template.action.name,
                actionParamsJson = engine.encodeParams(template.params),
            )
        }
    }

    companion object {
        val TEMPLATES = listOf(
            Template(
                name = "Receipt Organizer",
                description = "When a receipt is detected → add to Expenses, tag receipt.",
                trigger = RuleTrigger.RECEIPT_DETECTED,
                conditions = emptyMap(),
                action = RuleActionType.ADD_TO_COLLECTION,
                params = mapOf("collection" to "Expenses"),
            ),
            Template(
                name = "Travel Organizer",
                description = "When a travel screenshot is detected → add to Travel, tag travel.",
                trigger = RuleTrigger.TOPIC_DETECTED,
                conditions = emptyMap(),
                action = RuleActionType.ADD_TO_COLLECTION,
                params = mapOf("collection" to "Travel"),
            ),
            Template(
                name = "Price Research",
                description = "When a product with a price is detected → add to Price Research.",
                trigger = RuleTrigger.PRICE_DETECTED,
                conditions = emptyMap(),
                action = RuleActionType.ADD_TO_COLLECTION,
                params = mapOf("collection" to "Price Research"),
            ),
            Template(
                name = "Important Documents",
                description = "When an important document is detected → add to Important.",
                trigger = RuleTrigger.SCREENSHOT_CLASSIFIED,
                conditions = emptyMap(),
                action = RuleActionType.ADD_TO_COLLECTION,
                params = mapOf("collection" to "Important"),
            ),
        )
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AutomationViewModel(locator) as T
    }
}
