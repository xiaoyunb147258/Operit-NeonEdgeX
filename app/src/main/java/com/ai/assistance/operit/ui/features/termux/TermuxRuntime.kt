package com.ai.assistance.operit.ui.features.termux

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Termux 运行时：环境目录解析、bootstrap 解压、终端会话创建。
 *
 * 目录布局（应用私有）：
 *   filesDir/termux/usr   -> PREFIX（bootstrap 安装到这里）
 *   filesDir/termux/home  -> HOME
 *   filesDir/termux/tmp   -> TMPDIR
 *
 * bootstrap 直接从 APK assets 里的 termux-bootstrap-aarch64.zip 解压，
 * 不依赖 libtermux-bootstrap 原生库，避免编译期依赖。
 */
object TermuxRuntime {

    private const val TAG = "TermuxRuntime"
    private const val TRANSCRIPT_ROWS = 50000
    private const val BOOTSTRAP_ASSET = "termux-bootstrap-aarch64.zip"

    fun prefixDir(context: Context): File = File(context.filesDir, "termux/usr")
    fun homeDir(context: Context): File = File(context.filesDir, "termux/home")
    fun tmpDir(context: Context): File = File(context.filesDir, "termux/tmp")

    fun isReady(context: Context): Boolean = File(prefixDir(context), "bin/bash").exists()

    /**
     * 解压 bootstrap。已安装则跳过，除非 force=true。
     */
    @Synchronized
    fun installBootstrap(context: Context, force: Boolean = false): Boolean {
        val prefix = prefixDir(context)
        if (!force && File(prefix, "bin/bash").exists()) {
            AppLogger.i(TAG, "bootstrap already installed")
            return true
        }

        return try {
            homeDir(context).mkdirs()
            tmpDir(context).mkdirs()
            prefix.mkdirs()

            val marker = File(prefix, ".installed")
            marker.delete()

            context.assets.open(BOOTSTRAP_ASSET).use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name == "SYMLINKS.txt") {
                            zip.closeEntry()
                            entry = zip.nextEntry
                            continue
                        }
                        val target = File(prefix, name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> zip.copyTo(out) }
                            val executable = name.startsWith("bin/") ||
                                    name.startsWith("libexec/") ||
                                    name.startsWith("lib/apt/methods/") ||
                                    name.startsWith("lib/apt/apt-helper")
                            if (executable) target.setExecutable(true, false)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            marker.writeText("ok")
            AppLogger.i(TAG, "bootstrap installed to " + prefix.absolutePath)
            true
        } catch (t: Throwable) {
            AppLogger.e(TAG, "installBootstrap failed", t)
            false
        }
    }

    /**
     * 创建终端会话。环境未就绪时先安装 bootstrap。
     */
    fun createSession(
        context: Context,
        client: TerminalSessionClient = TermuxSessionClient()
    ): TerminalSession? {
        if (!isReady(context)) {
            val ok = installBootstrap(context)
            if (!ok) {
                AppLogger.e(TAG, "createSession aborted: bootstrap not installed")
                return null
            }
        }

        val prefix = prefixDir(context)
        val home = homeDir(context)
        val tmp = tmpDir(context)
        val bash = File(prefix, "bin/bash")
        if (!bash.exists()) {
            AppLogger.e(TAG, "bash missing at " + bash.absolutePath)
            return null
        }

        val env = arrayOf(
            "HOME=" + home.absolutePath,
            "PREFIX=" + prefix.absolutePath,
            "TMPDIR=" + tmp.absolutePath,
            "PATH=" + File(prefix, "bin").absolutePath + ":/system/bin:/system/xbin",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "SHELL=" + bash.absolutePath,
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "LD_LIBRARY_PATH=" + File(prefix, "lib").absolutePath
        )

        return TerminalSession(
            bash.absolutePath,
            home.absolutePath,
            arrayOf("--login"),
            env,
            TRANSCRIPT_ROWS,
            client
        )
    }
}
