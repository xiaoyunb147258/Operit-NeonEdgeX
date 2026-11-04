package com.ai.assistance.operit.core.terminal

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File

/**
 * 终端模式管理器。
 *
 * NATIVE：Operit 自带的 proot Ubuntu 终端
 * TERMUX：内嵌 Termux 内核，真正的 pkg/apt 环境
 *
 * 存储策略：SD 卡标志文件为唯一事实来源，AI 用文件工具直接写该文件即可切换；
 * SharedPreferences 作镜像缓存，文件不可读时回退。
 */
object TerminalModeManager {

    private const val TAG = "TerminalModeManager"

    const val MODE_NATIVE = "native"
    const val MODE_TERMUX = "termux"

    /** AI 与用户共用的标志文件 */
    const val FLAG_PATH = "/sdcard/Download/.operit_terminal_mode"

    private const val PREF = "terminal_mode_pref"
    private const val KEY_MODE = "mode"

    fun getMode(context: Context): String {
        runCatching {
            val f = File(FLAG_PATH)
            if (f.exists()) {
                val v = f.readText().trim().lowercase()
                if (v == MODE_TERMUX || v == MODE_NATIVE) {
                    putPref(context, v)
                    return v
                }
            }
        }.onFailure { AppLogger.w(TAG, "read flag failed: " + it.message) }

        val v = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_TERMUX) ?: MODE_TERMUX
        return if (v == MODE_NATIVE) MODE_NATIVE else MODE_TERMUX
    }

    fun setMode(context: Context, mode: String) {
        val m = if (mode.trim().lowercase() == MODE_TERMUX) MODE_TERMUX else MODE_NATIVE
        putPref(context, m)
        runCatching {
            val f = File(FLAG_PATH)
            f.parentFile?.mkdirs()
            f.writeText(m)
        }.onFailure { AppLogger.e(TAG, "write flag failed", it) }
    }

    fun isTermux(context: Context): Boolean = getMode(context) == MODE_TERMUX

    fun displayName(mode: String): String =
        if (mode == MODE_TERMUX) "Termux 终端" else "原生 Ubuntu 终端"

    private fun putPref(context: Context, mode: String) {
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, mode).apply()
        }
    }
}
