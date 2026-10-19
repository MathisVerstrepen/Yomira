// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Render server filter descriptors using Komikku's existing filter sheet. Position remains identity. */
internal fun List<ServerFilter>.toKomikkuFilters(): FilterList = FilterList(
    map { descriptor ->
        when (descriptor.type) {
            "TextFilter" -> object : Filter.Text(descriptor.name, descriptor.text.orEmpty()) {}
            "CheckBoxFilter" -> object : Filter.CheckBox(descriptor.name, descriptor.checked ?: false) {}
            "TriStateFilter" -> object : Filter.TriState(
                descriptor.name,
                when (descriptor.triState) {
                    "INCLUDE" -> 1
                    "EXCLUDE" -> 2
                    else -> 0
                },
            ) {}
            "SelectFilter" -> object : Filter.Select<String>(descriptor.name, descriptor.values.toTypedArray(), descriptor.selected ?: 0) {}
            "SortFilter" -> object : Filter.Sort(descriptor.name, descriptor.values.toTypedArray(), descriptor.sort?.let { Filter.Sort.Selection(it.index, it.ascending) }) {}
            "GroupFilter" -> object : Filter.Group<Filter<*>>(descriptor.name, descriptor.filters.toKomikkuFilters()) {}
            "SeparatorFilter" -> Filter.Separator()
            "HeaderFilter" -> Filter.Header(descriptor.name)
            else -> throw SuwayomiException(SuwayomiFailure.CONTRACT)
        }
    },
)

internal fun FilterList.toServerChanges(parent: List<Int> = emptyList()): List<JsonObject> = flatMapIndexed { index, filter ->
    val path = parent + index
    val change = when (filter) {
        is Filter.Text -> "textState" to JsonPrimitive(filter.state)
        is Filter.CheckBox -> "checkBoxState" to JsonPrimitive(filter.state)
        is Filter.TriState -> "triState" to JsonPrimitive(listOf("IGNORE", "INCLUDE", "EXCLUDE")[filter.state])
        is Filter.Select<*> -> "selectState" to JsonPrimitive(filter.state)
        is Filter.Sort -> filter.state?.let { state ->
            "sortState" to buildJsonObject {
                put("index", state.index)
                put("ascending", state.ascending)
            }
        }
        else -> null
    }
    if (filter is Filter.Group<*>) {
        FilterList(filter.state.filterIsInstance<Filter<*>>()).toServerChanges(path)
    } else {
        change?.let { listOf(sourceFilterChange(path, it.first, it.second)) }.orEmpty()
    }
}
// KMK <--
