package com.ai.assistance.operit.ui.features.chat.webview.computer

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.core.terminal.TerminalModeManager
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.rememberTerminalEnv
import com.ai.assistance.operit.terminal.main.TerminalScreen
import com.ai.assistance.operit.ui.features.termux.TermuxTerminalScreen

/**
 * AI 电脑模式：显示 AI 执行命令的终端。
 *
 * 顶部常驻切换条，可在「原生 Ubuntu」与「Termux」之间切换。
 * 切换结果写入 TerminalModeManager，AI 侧也读取同一个值。
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ComputerScreen() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(TerminalModeManager.getMode(context)) }
    var showSwitcher by remember { mutableStateOf(false) }

    // 仅原生模式下才初始化 proot 环境，避免 Termux 模式下无谓开销
    val terminalManager = remember { TerminalManager.getInstance(context) }
    val nativeEnv =
            if (mode == TerminalModeManager.MODE_NATIVE) {
                rememberTerminalEnv(terminalManager)
            } else null

    Column(modifier = Modifier.fillMaxSize()) {
        // ===== 顶部模式切换条 =====
        Row(
                modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                    "终端",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
            )
            ModeTab(
                    text = "原生 Ubuntu",
                    active = mode == TerminalModeManager.MODE_NATIVE
            ) {
                TerminalModeManager.setMode(context, TerminalModeManager.MODE_NATIVE)
                mode = TerminalModeManager.MODE_NATIVE
            }
            Spacer(Modifier.width(6.dp))
            ModeTab(
                    text = "Termux",
                    active = mode == TerminalModeManager.MODE_TERMUX
            ) {
                TerminalModeManager.setMode(context, TerminalModeManager.MODE_TERMUX)
                mode = TerminalModeManager.MODE_TERMUX
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showSwitcher = true }, modifier = Modifier.size(30.dp)) {
                Icon(
                        Icons.Default.Terminal,
                        contentDescription = "切换终端模式",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ===== 终端内容 =====
        Box(
                modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        // 拦截触摸，防止穿透到下层聊天界面；Termux 模式需要自己的输入，故不加拦截
                        .then(
                                if (mode == TerminalModeManager.MODE_NATIVE) {
                                    Modifier.pointerInput(Unit) {
                                        detectTapGestures(
                                                onTap = { },
                                                onDoubleTap = { },
                                                onLongPress = { },
                                                onPress = { }
                                        )
                                    }
                                } else Modifier
                        )
        ) {
            if (mode == TerminalModeManager.MODE_TERMUX) {
                // 背景铺黑，Termux 终端本身透明
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    TermuxTerminalScreen()
                }
            } else {
                val env = nativeEnv
                if (env != null) {
                    TerminalScreen(
                            env = env,
                            useLocalImeHandling = false,
                            checkUpdatesOnEnter = false,
                    )
                }
            }
        }
    }

    // ===== 详细切换对话框 =====
    if (showSwitcher) {
        AlertDialog(
                onDismissRequest = { showSwitcher = false },
                title = { Text("切换终端模式") },
                text = {
                    Column {
                        Text(
                                "当前：" + TerminalModeManager.displayName(mode),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(6.dp))
                        ModeRow(
                                title = "原生 Ubuntu 终端",
                                desc = "proot 容器，rootfs 已内置",
                                active = mode == TerminalModeManager.MODE_NATIVE
                        ) {
                            TerminalModeManager.setMode(context, TerminalModeManager.MODE_NATIVE)
                            mode = TerminalModeManager.MODE_NATIVE
                            showSwitcher = false
                        }
                        Spacer(Modifier.size(8.dp))
                        ModeRow(
                                title = "Termux 终端",
                                desc = "真正的 pkg/apt 手机本地环境",
                                active = mode == TerminalModeManager.MODE_TERMUX
                        ) {
                            TerminalModeManager.setMode(context, TerminalModeManager.MODE_TERMUX)
                            mode = TerminalModeManager.MODE_TERMUX
                            showSwitcher = false
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSwitcher = false }) { Text("关闭") }
                }
        )
    }
}

@Composable
private fun ModeTab(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (active) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
            onClick = onClick
    ) {
        Text(
                text,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModeRow(title: String, desc: String, active: Boolean, onClick: () -> Unit) {
    Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = if (active) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            onClick = onClick
    ) {
        Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                        desc,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (active) {
                Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
