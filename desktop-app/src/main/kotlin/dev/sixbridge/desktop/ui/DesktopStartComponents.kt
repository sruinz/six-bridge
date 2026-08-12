package dev.sixbridge.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import dev.sixbridge.desktop.profile.DesktopProfileOption
import org.jetbrains.skia.Image as SkiaImage

@Composable
internal fun DesktopStartHeader(modifier: Modifier = Modifier) {
    val brandImage = remember {
        val bytes = checkNotNull(
            DesktopStartComponents::class.java.classLoader
                .getResourceAsStream("images/six-bridge.png"),
        ).use { it.readBytes() }
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }
    Row(
        modifier = modifier.semantics { testTag = "input:brand" },
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            bitmap = brandImage,
            contentDescription = "SIX Bridge 아이콘",
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "SIX Bridge",
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "확장을 Samsung Internet용으로 안전하게 분석합니다.",
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.68f),
                fontSize = 15.sp,
            )
        }
    }
}

private object DesktopStartComponents

@Composable
internal fun DesktopStepRail(
    compact: Boolean,
    inputReady: Boolean,
    profileReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val steps = listOf(
        Triple("1", "확장 선택", "분석할 확장을 지정하세요"),
        Triple("2", "대상 profile", "분석에 사용할 profile을 확인하세요"),
        Triple("3", "분석 준비", "분석을 시작할 준비를 완료하세요"),
    )
    val readiness = listOf(true, inputReady, inputReady && profileReady)
    val tags = listOf("input:step-extension", "input:step-profile", "input:step-ready")

    if (compact) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            steps.forEachIndexed { index, step ->
                StepItem(
                    number = step.first,
                    title = step.second,
                    description = null,
                    active = readiness[index],
                    tag = tags[index],
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            steps.forEachIndexed { index, step ->
                StepItem(
                    number = step.first,
                    title = step.second,
                    description = step.third,
                    active = readiness[index],
                    tag = tags[index],
                )
            }
        }
    }
}

@Composable
private fun StepItem(
    number: String,
    title: String,
    description: String?,
    active: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics { testTag = tag },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = if (active) {
                MaterialTheme.colors.primary
            } else {
                MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(number, fontWeight = FontWeight.Bold)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                color = if (active) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            description?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.62f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
internal fun DesktopProfilePanel(
    selectedProfile: DesktopProfileOption?,
    profiles: List<DesktopProfileOption>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onProfileSelected: (String) -> Unit,
    onPickProfileJson: () -> Unit,
    onPickBrowserApk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var profileMenuExpanded by remember { mutableStateOf(false) }
    val verified = selectedProfile?.selectable == true
    val verifiedColor = desktopVerifiedColor()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colors.onSurface.copy(alpha = 0.14f),
                RoundedCornerShape(10.dp),
            ),
        shape = RoundedCornerShape(10.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircleOutline,
                    contentDescription = null,
                    tint = if (verified) verifiedColor else MaterialTheme.colors.onSurface.copy(alpha = 0.38f),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("대상 profile", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = selectedProfile?.explanation ?: "Samsung Internet profile 선택",
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.68f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (verified) {
                    Surface(
                        color = verifiedColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.semantics { testTag = "input:verified-badge" },
                    ) {
                        Text(
                            text = "검증됨",
                            color = verifiedColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                        )
                    }
                }
                Box {
                    OutlinedButton(
                        onClick = { profileMenuExpanded = true },
                        modifier = Modifier.semantics { testTag = "input:profile" },
                    ) {
                        Text("변경")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.ExpandMore, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = profileMenuExpanded,
                        onDismissRequest = { profileMenuExpanded = false },
                    ) {
                        profiles.forEach { profile ->
                            DropdownMenuItem(
                                enabled = profile.selectable,
                                onClick = {
                                    profileMenuExpanded = false
                                    onProfileSelected(profile.id)
                                },
                            ) {
                                Text(profile.explanation)
                            }
                        }
                    }
                }
            }
            Text(
                text = if (expanded) "고급 profile 접기" else "고급 profile 열기",
                color = MaterialTheme.colors.primary,
                modifier = Modifier
                    .clickable { onExpandedChange(!expanded) }
                    .semantics { testTag = "input:advanced-profile" }
                    .padding(vertical = 4.dp),
                fontWeight = FontWeight.Medium,
            )
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onPickProfileJson,
                        modifier = Modifier.semantics { testTag = "input:pick-profile-json" },
                    ) { Text("서명 profile JSON") }
                    OutlinedButton(
                        onClick = onPickBrowserApk,
                        modifier = Modifier.semantics { testTag = "input:pick-browser-apk" },
                    ) { Text("Samsung Internet APK") }
                }
            }
        }
    }
}

@Composable
internal fun DesktopReadinessBar(
    input: DesktopExtensionInput?,
    profile: DesktopProfileOption?,
    onAnalyze: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inputReady = input != null
    val profileReady = profile?.selectable == true
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { testTag = "input:ready-summary" },
        shape = RoundedCornerShape(10.dp),
        elevation = 0.dp,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.045f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("분석 준비", fontWeight = FontWeight.SemiBold)
                Text(
                    text = buildString {
                        append(if (inputReady) "입력 준비됨" else "입력 없음")
                        append(" · ")
                        append(if (profileReady) "profile 검증됨" else "profile 확인 필요")
                    },
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.58f),
                    fontSize = 13.sp,
                )
            }
            Button(
                onClick = onAnalyze,
                enabled = canAnalyze(input, profile, busy = false),
                modifier = Modifier.width(240.dp).semantics { testTag = "input:analyze" },
            ) {
                Text("확장 분석하기")
            }
        }
    }
}
