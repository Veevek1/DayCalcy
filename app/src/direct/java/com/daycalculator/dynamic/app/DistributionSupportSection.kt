package com.daycalculator.dynamic.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun DistributionSupportSection() {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    var showSupportDialog by remember { mutableStateOf(false) }

    if (showSupportDialog) {
        SupportDialog(onDismiss = { showSupportDialog = false })
    }

    Spacer(Modifier.height(10.dp))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { showSupportDialog = true },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_support_coffee),
                    contentDescription = "Buy me a coffee",
                    modifier = Modifier.padding(9.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("Buy me a coffee", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SupportDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val paypalUrl = "https://paypal.me/vivekjadhav05"
    val upiUri = "upi://pay?pa=veevek03%40axl&pn=DayCalcy&cu=INR"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.ic_support_coffee),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                )
                Spacer(Modifier.width(10.dp))
                Text("Buy me a coffee")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "If you find DayCalcy useful, you can support its developer.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                SupportMethodCard(
                    title = "PayPal",
                    leading = {
                        Text(
                            "P",
                            modifier = Modifier.wrapContentSize(Alignment.Center),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(paypalUrl)))
                        } catch (_: Exception) { }
                    }
                )
                Spacer(Modifier.height(8.dp))
                SupportMethodCard(
                    title = "Google Pay / UPI",
                    leading = {
                        Image(
                            painter = painterResource(id = R.drawable.ic_upi),
                            contentDescription = null,
                            modifier = Modifier.padding(9.dp),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                        )
                    },
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(upiUri)))
                        } catch (_: Exception) { }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun SupportMethodCard(
    title: String,
    leading: @Composable () -> Unit,
    onClick: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                content = leading
            )
            Spacer(Modifier.width(12.dp))
            Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
