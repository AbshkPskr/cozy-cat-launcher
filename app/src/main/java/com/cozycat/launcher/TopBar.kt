package com.cozycat.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Tab { Home, Apps }
enum class SystemAction { SEARCH, CAST, SETTINGS, ACCOUNT }

@Composable
fun TopBar(
    tab: Tab,
    onTab: (Tab) -> Unit,
    onAction: (SystemAction) -> Unit,
    tabRequester: FocusRequester,
    downTarget: () -> FocusRequester,
    modifier: Modifier = Modifier,
) {
    fun pill(t: Tab) = if (tab == t) Modifier.focusRequester(tabRequester) else Modifier
    Row(
        modifier
            .focusProperties { down = downTarget() }
            .fillMaxWidth()
            .padding(start = 26.dp, end = 26.dp, top = 47.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(58.dp))

        NavPill("Home", tab == Tab.Home, { onTab(Tab.Home) }, pill(Tab.Home), icon = Icons.Filled.Home)
        Spacer(Modifier.width(6.dp))
        NavPill("Apps", tab == Tab.Apps, { onTab(Tab.Apps) }, pill(Tab.Apps))

        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .width(1.dp)
                .height(28.dp)
                .background(Color.White.copy(alpha = 0.35f))
        )

        NavPill("Search", false, { onAction(SystemAction.SEARCH) }, icon = Icons.Filled.Search)

        Spacer(Modifier.weight(1f))

        IconPill(Icons.Outlined.Cast, "Cast", { onAction(SystemAction.CAST) })
        Spacer(Modifier.width(10.dp))
        IconPill(Icons.Filled.Settings, "Settings", { onAction(SystemAction.SETTINGS) })
        Spacer(Modifier.width(10.dp))
        AvatarPill(LauncherConfig.AVATAR_LETTER, { onAction(SystemAction.ACCOUNT) })
    }
}

@Composable
private fun Clock() {
    val fmt = androidx.compose.runtime.remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val now by produceState(fmt.format(Date())) {
        while (true) {
            value = fmt.format(Date())
            delay(1_000L * (60 - (System.currentTimeMillis() / 1000) % 60))
        }
    }
    Text(now, color = Latte, fontSize = 20.sp, fontWeight = FontWeight.Medium)
}
