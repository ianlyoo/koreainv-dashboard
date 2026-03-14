package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.Surface
import com.koreainv.dashboard.ui.theme.SurfaceAccent
import com.koreainv.dashboard.ui.theme.SurfaceBorder
import com.koreainv.dashboard.ui.theme.SurfaceGlass
import com.koreainv.dashboard.ui.theme.SurfaceGlassLight
import com.koreainv.dashboard.ui.theme.SurfacePrimary
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary

@Composable
fun PinUnlockScreen(
    errorMessage: String?,
    isLoading: Boolean,
    onUnlock: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }

    CredentialShell(
        title = stringResource(R.string.welcome_back),
        subtitle = stringResource(R.string.enter_pin_prompt),
        isLoading = isLoading,
        errorMessage = errorMessage,
    ) {
        Text(
            text = stringResource(R.string.enter_pin),
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            letterSpacing = 2.sp,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(4) { index ->
                val isFilled = index < pin.length
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isFilled) TextGold else Color.White.copy(alpha = 0.2f)),
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("DEL", "0", "OK"),
        )

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { key ->
                    NumpadButton(
                        text = key,
                        onClick = {
                            when (key) {
                                "DEL" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                "OK" -> if (pin.length == 4) onUnlock(pin)
                                else -> if (pin.length < 4) pin += key
                            }
                        },
                        isAction = key == "DEL" || key == "OK",
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun CredentialShell(
    title: String,
    subtitle: String,
    isLoading: Boolean,
    errorMessage: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
    ) {
        DecorativeBackdrop()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(Surface.copy(alpha = 0.94f))
                    .border(1.dp, SurfaceBorder.copy(alpha = 0.88f), RoundedCornerShape(32.dp))
                    .padding(28.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.korea_inv_dashboard),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextGold,
                        letterSpacing = 1.4.sp,
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Surface.copy(alpha = 0.85f))
                            .border(1.dp, SurfaceBorder, RoundedCornerShape(22.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.displayMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(22.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = content,
                    )
                }
            }
        }

        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 120.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = TextGold)
            }
        }
    }
}

@Composable
private fun DecorativeBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Background,
                        Surface,
                        Background,
                    ),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(104.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            SurfaceGlassLight.copy(alpha = 0.42f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(132.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            SurfaceGlass.copy(alpha = 0.3f),
                        ),
                    ),
                ),
        )
    }
}

@Composable
fun NumpadButton(text: String, onClick: () -> Unit, isAction: Boolean = false) {
    val buttonText = when (text) {
        "DEL" -> stringResource(R.string.delete)
        "OK" -> stringResource(R.string.ok)
        else -> text
    }

    TextButton(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        shape = CircleShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (isAction) TextGold else Color.White,
        ),
    ) {
        Text(
            text = buttonText,
            fontSize = if (isAction) 16.sp else 24.sp,
            fontWeight = if (isAction) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
