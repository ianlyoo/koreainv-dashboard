package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.ui.theme.LocalDashboardColors

/** No internal horizontal inset: labels share the surrounding content's 20dp gutter. */
@Composable
internal fun InsightDisclosureRow(label: String, expanded: Boolean, onClick: () -> Unit, prominent: Boolean = false) {
    val colors = LocalDashboardColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { stateDescription = if (expanded) "펼쳐짐" else "접힘" }
            .clickable(role = Role.Button, onClickLabel = if (expanded) "접기" else "펼치기", onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null, modifier = Modifier.size(24.dp), tint = colors.textSecondary)
    }
}
