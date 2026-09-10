package com.carriez.flutter_hbb

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.KeyEvent as KeyEventAndroid
import hbb.KeyEventConverter
import hbb.MessageOuterClass.KeyEvent
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import rikka.shizuku.Shizuku

/**
 * Remote pointer/key injection for API < 24 via a privileged shell.
 *
 * Accessibility `dispatchGesture` is only available from API 24. On older
 * devices (minSdk 22) fall back to a long-lived shell that runs `input`:
 * Shizuku (ADB/shell uid) first, then `su`.
 */
object ShellInputInjector {
    private const val TAG = "ShellInput"
    private const val CLICK_SLOP = 8
    private const val TAP_MAX_MS = 300L

    private enum class Backend { NONE, SHIZUKU, SU }

    @Volatile
    private var backend = Backend.NONE
    @Volatile
    private var shell: Process? = null
    @Volatile
    private var writer: BufferedWriter? = null
    private val stdoutDrain = LinkedBlockingQueue<String>()

    private var downX = 0
    private var downY = 0
    private var isDown = false
    private var downAt = 0L

    @Volatile
    private var suProbed = false
    @Volatile
    private var suAvailable = false
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            try {
                Shizuku.addBinderReceivedListenerSticky {
                    Log.i(TAG, "Shizuku binder received (sticky/listener)")
                }
                Shizuku.addBinderDeadListener {
                    Log.w(TAG, "Shizuku binder dead")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Shizuku listener register failed: ${e.message}")
            }
        }
    }

    fun isReady(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return false
        if (ensureShell()) return true
        return false
    }

    /** True when either accessibility gestures (N+) or privileged shell (pre-N) can inject. */
    fun isRemoteInputReady(): Boolean {
        return InputService.isOpen || isReady()
    }

    fun onPointer(mask: Int, rawX: Int, rawY: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return
        if (!ensureShell()) return

        val x = (rawX * SCREEN_INFO.scale).coerceAtLeast(0)
        val y = (rawY * SCREEN_INFO.scale).coerceAtLeast(0)

        when (mask) {
            LEFT_DOWN -> {
                isDown = true
                downX = x
                downY = y
                downAt = System.currentTimeMillis()
            }
            LEFT_MOVE -> {
                // path is synthesized on button-up; intermediate points are unused
            }
            LEFT_UP -> {
                if (!isDown) return
                isDown = false
                val dur = System.currentTimeMillis() - downAt
                val dist = abs(x - downX) + abs(y - downY)
                when {
                    dist < CLICK_SLOP && dur < TAP_MAX_MS -> tap(x, y)
                    dist < CLICK_SLOP -> swipe(downX, downY, x, y, dur.coerceIn(300, 3000))
                    else -> swipe(downX, downY, x, y, dur.coerceIn(50, 2000))
                }
            }
            RIGHT_UP -> swipe(x, y, x, y, 600)
            BACK_UP -> keyEvent(KeyEventAndroid.KEYCODE_BACK)
            WHEEL_BUTTON_DOWN, WHEEL_BUTTON_UP -> keyEvent(KeyEventAndroid.KEYCODE_HOME)
            WHEEL_DOWN -> swipe(x, y, x, y - 200, 80)
            WHEEL_UP -> swipe(x, y, x, y + 200, 80)
        }
    }

    fun onKey(data: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return
        if (!ensureShell()) return
        try {
            val keyEvent = KeyEvent.parseFrom(data)
            var textToCommit: String? = null
            if (keyEvent.hasSeq()) {
                textToCommit = keyEvent.seq
            } else if (keyEvent.hasChr() && (keyEvent.down || keyEvent.press)) {
                val chr = keyEvent.chr
                if (chr != null && chr in 32..126) {
                    textToCommit = String(Character.toChars(chr))
                }
            }
            val ke = KeyEventConverter.toAndroidKeyEvent(keyEvent)
            if (ke.keyCode == KeyEventAndroid.KEYCODE_VOLUME_UP ||
                ke.keyCode == KeyEventAndroid.KEYCODE_VOLUME_DOWN ||
                ke.keyCode == KeyEventAndroid.KEYCODE_VOLUME_MUTE
            ) {
                return
            }
            if (textToCommit != null) {
                text(textToCommit)
            } else if (ke.keyCode != 0) {
                keyEvent(ke.keyCode)
                if (keyEvent.press) {
                    // input keyevent already sends down+up for a single code
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onKey failed", e)
        }
    }

    private fun tap(x: Int, y: Int) = send("input tap $x $y")

    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) =
        send("input swipe $x1 $y1 $x2 $y2 ${durationMs.coerceIn(1, 3000)}")

    private fun keyEvent(code: Int) = send("input keyevent $code")

    private fun text(value: String) {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", " ")
            .replace("\r", "")
        send("input text '$escaped'")
    }

    @Synchronized
    private fun ensureShell(): Boolean {
        val current = shell
        if (current != null && writer != null) {
            try {
                current.exitValue()
                closeShell()
            } catch (_: IllegalThreadStateException) {
                return true
            } catch (_: Exception) {
                closeShell()
            }
        }

        if (tryShizuku()) return true
        if (trySu()) return true
        return false
    }

    private fun tryShizuku(): Boolean {
        return try {
            val ping = try {
                Shizuku.pingBinder()
            } catch (e: Throwable) {
                Log.w(TAG, "Shizuku.pingBinder threw: ${e.message}")
                false
            }
            Log.i(TAG, "Shizuku pingBinder=$ping")
            if (!ping) return false

            val preV11 = try {
                Shizuku.isPreV11()
            } catch (e: Throwable) {
                false
            }
            Log.i(TAG, "Shizuku isPreV11=$preV11 sdk=${Build.VERSION.SDK_INT}")

            if (!preV11) {
                val granted = try {
                    Shizuku.checkSelfPermission() ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                } catch (e: Throwable) {
                    Log.w(TAG, "checkSelfPermission: ${e.message}")
                    true
                }
                if (!granted) {
                    Log.w(TAG, "Shizuku v11+ permission not granted")
                    return false
                }
            } else {
                val tokenOk = tryLegacyTokenUnlock()
                Log.i(TAG, "Shizuku pre-v11 token unlock=$tokenOk")
            }

            val p = openShizukuProcess()
            if (p == null) {
                Log.w(TAG, "Shizuku newProcess returned null")
                return false
            }
            attachShell(p, Backend.SHIZUKU)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku unavailable: ${e.message}", e)
            false
        }
    }

    /**
     * Shizuku 3.6.1 / API 22: manager token + setUidToken so newProcess is allowed
     * unless the manager already pushed setUidPermissionPre23 on ALLOW.
     */
    private fun tryLegacyTokenUnlock(): Boolean {
        val ctx = appContext ?: return false
        return try {
            val uri = Uri.parse("content://moe.shizuku.manager.tokenprovider")
            val reply = ctx.contentResolver.call(uri, "getToken", null, null)
            if (reply == null) {
                Log.w(TAG, "TokenProvider null (allow RustDesk in Shizuku manager first)")
                return false
            }
            val most = reply.getLong("moe.shizuku.privileged.api.intent.extra.TOKEN_MOST_SIG", 0L)
            val least = reply.getLong("moe.shizuku.privileged.api.intent.extra.TOKEN_LEAST_SIG", 0L)
            if (most == 0L && least == 0L) {
                Log.w(TAG, "TokenProvider empty token")
                return false
            }
            val token = UUID(most, least)
            Log.i(TAG, "got legacy token, calling setUidToken")
            val method = Shizuku::class.java.getDeclaredMethod(
                "setUidToken",
                String::class.java
            )
            method.isAccessible = true
            val ok = method.invoke(null, token.toString()) as? Boolean ?: false
            Log.i(TAG, "setUidToken result=$ok")
            ok
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Shizuku.setUidToken not present: ${e.message}")
            false
        } catch (e: Throwable) {
            Log.w(TAG, "legacy token unlock failed: ${e.message}")
            false
        }
    }

    private fun openShizukuProcess(): Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val p = method.invoke(null, arrayOf("sh"), null, null) as? Process
            Log.i(TAG, "Shizuku.newProcess -> $p")
            p
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Shizuku.newProcess not present")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku.newProcess failed: ${e.message}")
            null
        }
    }

    private fun trySu(): Boolean {
        if (suProbed && !suAvailable) return false
        suProbed = true
        val ok = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            out.contains("uid=0")
        } catch (e: Exception) {
            Log.w(TAG, "su probe failed: ${e.message}")
            false
        }
        suAvailable = ok
        if (!ok) return false
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su"))
            attachShell(p, Backend.SU)
            true
        } catch (e: Exception) {
            Log.w(TAG, "su shell open failed: ${e.message}")
            false
        }
    }

    private fun attachShell(p: Process, backendKind: Backend) {
        shell = p
        backend = backendKind
        writer = BufferedWriter(OutputStreamWriter(p.outputStream))
        Thread({
            try {
                val r = BufferedReader(InputStreamReader(p.inputStream))
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isNotEmpty()) Log.d(TAG, "shell: $line")
                    stdoutDrain.offer(line)
                }
            } catch (_: Exception) {
            }
        }, "rd-shell-out").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "privileged shell attached via $backendKind")
    }

    private fun send(cmd: String) {
        val w = writer ?: return
        try {
            w.write(cmd)
            w.newLine()
            w.flush()
        } catch (e: Exception) {
            Log.e(TAG, "send failed: $cmd", e)
            closeShell()
        }
    }

    @Synchronized
    private fun closeShell() {
        try {
            writer?.write("exit\n")
            writer?.flush()
        } catch (_: Exception) {
        }
        try {
            shell?.destroy()
        } catch (_: Exception) {
        }
        writer = null
        shell = null
        backend = Backend.NONE
    }

    /** Request Shizuku permission from an Activity context if binder is up but not granted. */
    fun requestShizukuPermissionIfNeeded(requestCode: Int): Boolean {
        return try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return false
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return true
            }
            if (!Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(requestCode)
            }
            false
        } catch (e: Throwable) {
            false
        }
    }

    fun awaitOutput(timeoutMs: Long): String? {
        return stdoutDrain.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
