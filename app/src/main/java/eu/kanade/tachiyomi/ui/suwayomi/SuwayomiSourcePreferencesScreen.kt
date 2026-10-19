// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.ServerPreference
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.allowed
import eu.kanade.tachiyomi.data.suwayomi.changeSourcePreference
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class SuwayomiSourcePreferencesScreen(
    private val sessionId: String,
    private val sourceId: Long,
    private val sourceName: String? = null,
    private val sourceLanguage: String? = null,
) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { SuwayomiAdministrationModel(sessionId, sourceId) }
        val state by model.state.collectAsState()
        val snackbar = rememberRefreshErrors(state.error, state.hasLoaded) { model.load() }
        val context = androidx.compose.ui.platform.LocalContext.current
        val identity = listOfNotNull(sourceName, sourceLanguage?.let { LocaleHelper.getSourceDisplayName(it, context) }).joinToString(" · ")
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.load() }
        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(KMR.strings.suwayomi_source_settings),
                    subtitle = identity.takeIf { it.isNotBlank() },
                    navigateUp = navigator::pop,
                    actions = {
                        IconButton(onClick = { model.load() }, enabled = !state.loading) {
                            Icon(Icons.Outlined.Refresh, stringResource(KMR.strings.suwayomi_reload))
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                RefreshProgress(state.loading)
                if (!state.hasLoaded) state.error?.let { ConnectionError(it, { model.load() }) }
                LazyColumn(Modifier.weight(1f)) {
                    item(key = "shared-notice") { SharedSourceNotice() }
                    if (state.hasLoaded && state.user?.allowed("MANAGE_SOURCE_PREFERENCES") == false) {
                        item { Text(failureText(SuwayomiFailure.PERMISSION_DENIED), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                    } else {
                        if (state.hasLoaded && state.preferences.none { it.visible }) {
                            item { TextPreferenceWidget(title = stringResource(KMR.strings.suwayomi_source_settings_empty)) }
                        }
                        // Preserve original positions, including hidden preferences, for the server contract.
                        itemsIndexed(state.preferences, key = { index, pref -> "$index-${pref.key}-${pref.type}" }) { index, preference ->
                            if (preference.visible) {
                                SourcePreferenceRow(
                                    preference = preference,
                                    enabled = !state.loading && state.user?.allowed("MANAGE_SOURCE_PREFERENCES") == true,
                                ) { field, value ->
                                    model.load { changeSourcePreference(sourceId, index, preference, field, value) }.join()
                                    model.state.value.error
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedSourceNotice() {
    var details by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { details = true }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(KMR.strings.suwayomi_source_settings_shared), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            text = { Text(stringResource(KMR.strings.suwayomi_shared_source_notice)) },
            confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(KMR.strings.suwayomi_close)) } },
        )
    }
}

@Composable
private fun SourcePreferenceRow(preference: ServerPreference, enabled: Boolean, change: suspend (String, JsonElement) -> SuwayomiFailure?) {
    val editable = enabled && preference.enabled
    val title = preference.title.orEmpty()
    val entries = preference.entryValues.mapIndexedNotNull { index, value -> preference.entries.getOrNull(index)?.let { value to it } }.toMap()
    val value = when (preference.type) {
        "ListPreference" -> (preference.valueString ?: preference.defaultString)?.let { entries[it] ?: it }
        "MultiSelectListPreference" -> (preference.valueList ?: preference.defaultList.orEmpty()).map { entries[it] ?: it }.joinToString().ifBlank { stringResource(KMR.strings.suwayomi_value_none) }
        "EditTextPreference" -> stringResource(if ((preference.valueString ?: preference.defaultString).isNullOrBlank()) KMR.strings.suwayomi_value_not_set else KMR.strings.suwayomi_value_configured)
        else -> null
    }
    val summary = if (preference.type in listOf("EditTextPreference", "MultiSelectListPreference")) {
        preference.summary?.replace("%1\$s", value.orEmpty())?.replace("%s", value.orEmpty())
    } else {
        preference.displaySummary()
    }
    val subtitle = buildAnnotatedString {
        if (!value.isNullOrBlank()) withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(value) }
        summary?.takeIf { it.isNotBlank() && it != value }?.let {
            if (length > 0) append('\n')
            append(it)
        }
    }
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable(preference.key, preference.type, preference.entryValues) { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val boolean = preference.type == "SwitchPreference" || preference.type == "CheckBoxPreference"
    val supported = boolean || preference.type in listOf("ListPreference", "MultiSelectListPreference", "EditTextPreference")
    val canEdit = editable && supported && (preference.type !in listOf("ListPreference", "MultiSelectListPreference") || entries.isNotEmpty())
    TextPreferenceWidget(
        modifier = Modifier.alpha(if (canEdit) 1f else .5f),
        title = title,
        subtitle = if (supported) subtitle else stringResource(KMR.strings.suwayomi_unsupported_preference),
        widget = if (boolean) {
            { Switch(checked = preference.valueBool ?: preference.defaultBool ?: false, onCheckedChange = null, enabled = editable) }
        } else if (preference.type == "EditTextPreference") {
            { Icon(Icons.Outlined.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
        } else {
            null
        },
        onPreferenceClick = if (canEdit) {
            {
                if (boolean) {
                    scope.launch { change(if (preference.type == "SwitchPreference") "switchState" else "checkBoxState", JsonPrimitive(!(preference.valueBool ?: preference.defaultBool ?: false))) }
                } else {
                    text = preference.valueString ?: preference.defaultString.orEmpty()
                    selection = preference.valueList ?: preference.defaultList.orEmpty()
                    editing = true
                }
            }
        } else {
            null
        },
    )
    if (editing) SourcePreferenceDialog(preference, entries, text, { text = it }, selection, { selection = it }, change) { editing = false }
}

@Composable
private fun SourcePreferenceDialog(
    preference: ServerPreference,
    entries: Map<String, String>,
    text: String,
    onTextChange: (String) -> Unit,
    selection: List<String>,
    onSelectionChange: (List<String>) -> Unit,
    change: suspend (String, JsonElement) -> SuwayomiFailure?,
    dismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<SuwayomiFailure?>(null) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    val single = preference.type == "ListPreference"
    fun save(field: String, value: JsonElement) {
        scope.launch {
            saving = true
            error = change(field, value)
            saving = false
            if (error == null) dismiss()
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) dismiss() },
        title = { Text(preference.title.orEmpty()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (preference.type == "EditTextPreference") {
                    OutlinedTextField(
                        value = text,
                        onValueChange = onTextChange,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = if (revealed) 2 else 1,
                        maxLines = 5,
                        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        trailingIcon = {
                            IconButton(onClick = { revealed = !revealed }) {
                                Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(if (revealed) KMR.strings.suwayomi_hide_value else KMR.strings.suwayomi_show_value))
                            }
                        },
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        entries.forEach { (value, label) ->
                            item(key = value) {
                                if (single) {
                                    Row(
                                        Modifier.fillMaxWidth().selectable(selected = text == value, enabled = !saving, role = Role.RadioButton, onClick = {
                                            onTextChange(value)
                                            if ((preference.valueString ?: preference.defaultString) != value) save("listState", JsonPrimitive(value)) else dismiss()
                                        }).padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = text == value, onClick = null, enabled = !saving)
                                        Text(label, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                                    }
                                } else {
                                    LabeledCheckbox(label, value in selection, enabled = !saving, onCheckedChange = { checked -> onSelectionChange(if (checked) selection + value else selection - value) })
                                }
                            }
                        }
                    }
                }
                error?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (saving) CircularProgressIndicator(Modifier.size(24.dp))
            }
        },
        confirmButton = {
            if (!single) {
                TextButton(
                    enabled = !saving && if (preference.type == "EditTextPreference") text != (preference.valueString ?: preference.defaultString.orEmpty()) else selection.toSet() != (preference.valueList ?: preference.defaultList.orEmpty()).toSet(),
                    onClick = {
                        if (preference.type == "EditTextPreference") save("editTextState", JsonPrimitive(text)) else save("multiSelectState", JsonArray(selection.map(::JsonPrimitive)))
                    },
                ) { Text(stringResource(KMR.strings.suwayomi_save)) }
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !saving) { Text(stringResource(KMR.strings.suwayomi_cancel)) } },
    )
}
// KMK <--
