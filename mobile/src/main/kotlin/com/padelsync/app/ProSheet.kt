package com.padelsync.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netsports.core.pro.ProPlan
import com.netsports.core.pro.ProStatus
import com.netsports.core.pro.ProText
import com.padelsync.kit.ProOffer
import com.padelsync.kit.ProStore

/**
 * The Pro sheet: what Pro does, the plans with the store's prices, and the
 * purchase. Opened from "Host a match" or "Play with others" once the trial
 * is over, and closes by itself when Pro is bought.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProSheet(store: ProStore, onDismiss: () -> Unit) {
    val ui by store.ui.collectAsState()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var chosen by remember { mutableStateOf(ProPlan.YEARLY) }

    LaunchedEffect(Unit) { store.refresh() }
    // Bought: the sheet has done its job.
    LaunchedEffect(ui.status.kind) {
        if (ui.status.kind == ProStatus.Kind.OWNED) onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = {
            store.clearMessage()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = Palette.Surface,
        contentColor = Palette.OnBackground,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 18.dp)
                .navigationBarsPadding(),
        ) {
            Text(ProText.TITLE, fontSize = 26.sp, fontWeight = FontWeight.Black, color = Palette.Accent)
            Spacer(Modifier.height(6.dp))
            if (ui.status.kind == ProStatus.Kind.EXPIRED) {
                Text(ProText.TRIAL_ENDED, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
            }
            Text(ProText.PITCH, fontSize = 15.sp, color = Palette.Muted)
            Spacer(Modifier.height(12.dp))
            for (benefit in ProText.BENEFITS) {
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("✓", color = Palette.Accent, fontWeight = FontWeight.Black, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(benefit, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(8.dp))

            if (ui.offers.isEmpty()) {
                // No answer from Google Play yet, or no Play on this device.
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Text(ProText.UNAVAILABLE, color = Palette.Muted, textAlign = TextAlign.Center)
                }
            } else {
                for (offer in ui.offers) {
                    PlanRow(offer, selected = offer.plan == chosen) { chosen = offer.plan }
                }
            }

            ui.message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 14.sp, color = Palette.Accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(14.dp))
            val canBuy = ui.offers.any { it.plan == chosen } && !ui.busy
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (canBuy) Palette.Accent else Palette.SurfaceHigh)
                    .clickable(enabled = canBuy, role = Role.Button) {
                        context.findActivity()?.let { store.buy(it, chosen) }
                    }
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (ui.busy) {
                    CircularProgressIndicator(Modifier.height(24.dp).width(24.dp), color = Palette.OnBackground, strokeWidth = 3.dp)
                } else {
                    Text(
                        ProText.CONTINUE,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = if (canBuy) Palette.OnAccent else Palette.Muted,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                SmallLink("Billed by Google Play", enabled = false) {}
                SmallLink(" · ${ProText.RESTORE}", enabled = !ui.busy) { store.restore() }
                SmallLink(" · Manage", enabled = true) { context.openSubscriptions() }
            }
        }
    }
}

@Composable
private fun PlanRow(offer: ProOffer, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.SurfaceHigh)
            .border(2.dp, if (selected) Palette.Accent else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ProText.planName(offer.plan), fontSize = 18.sp, fontWeight = FontWeight.Black)
                offer.badge?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = Palette.OnAccent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Palette.Gold)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(offer.detail, fontSize = 13.sp, color = Palette.Muted)
        }
        Text(offer.price, fontSize = 19.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SmallLink(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp,
        color = Palette.Muted,
        modifier = if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
    )
}

/** Google Play's page for the subscriptions of this account, where a plan is cancelled. */
private fun Context.openSubscriptions() {
    val uri = Uri.parse(
        "https://play.google.com/store/account/subscriptions?sku=${ProStore.SUBSCRIPTION_ID}&package=$packageName",
    )
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
