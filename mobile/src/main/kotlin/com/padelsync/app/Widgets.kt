package com.padelsync.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netsports.core.match.Roster
import kotlinx.coroutines.CoroutineScope

/** A titled row of mutually exclusive choices. */
@Composable
fun <T> OptionGroup(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(title, fontSize = 15.sp, color = Palette.Muted, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in options) {
                val isSelected = option == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) Palette.Accent else Palette.SurfaceHigh)
                        .clickable(role = Role.RadioButton) { onSelect(option) }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) Palette.OnAccent else Palette.OnBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A setting that is either on or off, with a line explaining it. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    caption: String? = null,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Palette.OnBackground else Palette.Muted,
            )
            if (caption != null) Text(caption, fontSize = 14.sp, color = Palette.Muted)
        }
        Spacer(Modifier.width(12.dp))
        // The row handles the tap, so the switch itself is display only.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** One player's name. */
@Composable
fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    last: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(clip(it)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
        ),
        modifier = modifier,
    )
}

/** Names are cut to 20 bytes on the wire; this keeps typing within sight of that. */
private const val MAX_NAME_CHARS = 20

/** Cuts typed text to [MAX_NAME_CHARS] without leaving half an emoji behind. */
private fun clip(text: String): String {
    if (text.length <= MAX_NAME_CHARS) return text
    val cut = text.take(MAX_NAME_CHARS)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/**
 * The roster for the names typed into the form.
 *
 * In doubles the order of a team's two players is its serving order, so a
 * name typed only into the second field must stay second: the empty first
 * field is filled in rather than dropped.
 */
fun rosterOf(doubles: Boolean, a1: String, a2: String, b1: String, b2: String): Roster {
    if (!doubles) return Roster.of(listOf(a1), listOf(b1))
    fun team(first: String, second: String): List<String> =
        if (first.isBlank() && second.isNotBlank()) listOf("Player 1", second) else listOf(first, second)
    return Roster.of(team(a1, a2), team(b1, b2))
}

/**
 * Runs [block] each time [key] changes, but not for the value it has when
 * this first appears. Use it for one-off reactions (a buzz, a note) that
 * must not replay just because the screen was rebuilt.
 */
@Composable
fun OnChange(key: Any?, block: suspend CoroutineScope.() -> Unit) {
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(key) {
        if (first[0]) first[0] = false else block()
    }
}
