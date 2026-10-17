package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.data.WidgetPreferences
import com.mineserve.mobile.data.WidgetUpdater
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Muted
import androidx.compose.ui.res.stringResource

@Composable
fun WidgetSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var actionsEnabled by remember { mutableStateOf(WidgetPreferences.areActionsEnabled(context)) }
    var refreshed by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.widget_settings_title), onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
        ) {
            Text(
                stringResource(R.string.widget_settings_intro),
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = McSpacing.Page)
            )

            McCard(title = stringResource(R.string.widget_settings_types_title)) {
                WidgetTypeRow(R.string.widget_overview_title, R.string.widget_overview_size, R.string.widget_overview_desc)
                WidgetTypeRow(R.string.widget_event_title, R.string.widget_event_size, R.string.widget_event_desc)
                WidgetTypeRow(R.string.widget_console_title, R.string.widget_console_size, R.string.widget_console_desc)
                WidgetTypeRow(R.string.widget_mod_plugin_title, R.string.widget_mod_plugin_size, R.string.widget_mod_plugin_desc)
            }

            McCard(title = stringResource(R.string.widget_settings_add_title)) {
                Text(stringResource(R.string.widget_settings_add_steps), fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.widget_settings_scope_hint), fontSize = 11.sp, color = Muted)
            }

            McCard(title = stringResource(R.string.widget_settings_behavior_title)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.widget_settings_actions), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.widget_settings_actions_hint), fontSize = 11.sp, color = Muted)
                    }
                    Switch(
                        checked = actionsEnabled,
                        onCheckedChange = {
                            actionsEnabled = it
                            WidgetPreferences.setActionsEnabled(context, it)
                            WidgetUpdater.refresh(context)
                        }
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        refreshed = true
                        WidgetUpdater.refresh(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                    shape = McShape.Control
                ) {
                    Text(stringResource(R.string.widget_settings_refresh))
                }
                if (refreshed) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.widget_settings_refresh_done), fontSize = 11.sp, color = Muted)
                }
            }
        }
    }
}

@Composable
private fun WidgetTypeRow(title: Int, size: Int, description: Int) {
    Column(Modifier.padding(bottom = 9.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(title), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(size), fontSize = 11.sp, color = Indigo)
        }
        Text(stringResource(description), fontSize = 11.sp, color = Muted)
    }
}
