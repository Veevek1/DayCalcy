package com.daycalculator.dynamic.app

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun DistributionSupportSection() {
    var showSupportDialog by remember { mutableStateOf(false) }
    var showBitcoinDialog by remember { mutableStateOf(false) }

    if (showSupportDialog) {
        SupportDialog(
            onDismiss = { showSupportDialog = false },
            onBitcoinClick = { showBitcoinDialog = true }
        )
    }
    if (showBitcoinDialog) {
        BitcoinDonationDialog(onDismiss = { showBitcoinDialog = false })
    }

    Spacer(Modifier.height(10.dp))
    // Keep this card visually matched to the Star on GitHub card in About.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { showSupportDialog = true },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
private fun SupportDialog(onDismiss: () -> Unit, onBitcoinClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val koFiUrl = "https://ko-fi.com/veevek"
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
                    title = "Ko-fi",
                    leading = {
                        Image(
                            painter = painterResource(id = R.drawable.ic_kofi),
                            contentDescription = "Ko-fi",
                            modifier = Modifier.size(26.dp)
                        )
                    },
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(koFiUrl)))
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
                Spacer(Modifier.height(8.dp))
                SupportMethodCard(
                    title = "Bitcoin",
                    leading = {
                        Image(
                            painter = painterResource(id = R.drawable.ic_bitcoin),
                            contentDescription = "Bitcoin",
                            modifier = Modifier.size(26.dp)
                        )
                    },
                    onClick = onBitcoinClick
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private const val BITCOIN_DONATION_ADDRESS = "bc1qew0mekperhg8nc4ql0ce4a7z2xju2080lsrcml"

@Composable
private fun BitcoinDonationDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val qrBitmap = remember {
        runCatching { createBitcoinQrBitmap(BITCOIN_DONATION_ADDRESS) }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Donate with Bitcoin") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "To donate, open your Bitcoin wallet and scan this QR code using the Send option.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                if (qrBitmap != null) {
                    Surface(
                        color = androidx.compose.ui.graphics.Color.White,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(224.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Bitcoin donation QR code",
                            modifier = Modifier.padding(8.dp).fillMaxSize()
                        )
                    }
                } else {
                    Text("QR code could not be generated.", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = BITCOIN_DONATION_ADDRESS,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { copyBitcoinAddress(context) }
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Tap the address to copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private fun copyBitcoinAddress(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin address", BITCOIN_DONATION_ADDRESS))
    Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
}

private fun createBitcoinQrBitmap(address: String): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(address, BarcodeFormat.QR_CODE, 512, 512, hints)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            bitmap.setPixel(
                x,
                y,
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            )
        }
    }
    return bitmap
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
            containerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 1.dp else 0.dp)
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
