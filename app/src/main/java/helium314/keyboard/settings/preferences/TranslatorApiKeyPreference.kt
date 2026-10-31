// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.TextInputDialog

/** Claude API key for the translator; the key itself is never shown in the description. */
@Composable
fun TranslatorApiKeyPreference(setting: Setting) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val prefs = LocalContext.current.prefs()
    val key = prefs.getString(setting.key, "").orEmpty()
    Preference(
        name = setting.title,
        onClick = { showDialog = true },
        description = if (key.isBlank()) stringResource(R.string.translator_api_key_unset)
            else stringResource(R.string.translator_api_key_set, key.takeLast(4))
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { prefs.edit { putString(setting.key, helium314.keyboard.latin.translate.ClaudeTranslator.extractKey(it) ?: it.trim()) } },
            initialText = "",
            title = { Text(setting.title) },
            checkTextValid = { it.isNotBlank() },
            onNeutral = { prefs.edit { remove(setting.key) }; showDialog = false },
            neutralButtonText = stringResource(R.string.delete)
        )
    }
}
