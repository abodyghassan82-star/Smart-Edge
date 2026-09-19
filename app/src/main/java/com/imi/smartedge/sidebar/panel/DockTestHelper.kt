package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Shared dock-test logic used by both DockTestReceiver (notification buttons)
 * and the in-app dock-test card in MainActivity.
 *
 * Every public method appends to a caller-supplied StringBuilder so the
 * caller can display a scrollable log.  The same log is also written to
 * logcat under tag "DockTest".
 */
object DockTestHelper {

    private const val TAG = "DockTest"
    private const val PREFS_NAME = "dock_test_state"
    private const val KEY_TASK_ID = "task_id"
    private const val KEY_LEFT = "left"
    private const val KEY_TOP = "top"
    private const val KEY_RIGHT = "right"
    private const val KEY_BOTTOM = "bottom"

    // ── public API ────────────────────────────────────────────────────────

    /**
     * Returns true if Shizuku is running and permission is granted.
     * Appends diagnostic lines to [log].
     */
    fun checkShizuku(log: StringBuilder): Boolean {
        log.appendLine("── Shizuku status ──")

        val running = try { Shizuku.pingBinder() } catch (_: Exception) { false }
        if (!running) {
            log.appendLine("  Service: NOT RUNNING")
            log.appendLine("  → Start the Shizuku app and enable it, then retry.")
            Log.e(TAG, "Shizuku not running")
            return false
        }
        log.appendLine("  Service: running")

        val perm = try { Shizuku.checkSelfPermission() } catch (_: Exception) { PackageManager.PERMISSION_DENIED }
        if (perm != PackageManager.PERMISSION_GRANTED) {
            log.appendLine("  Permission: NOT GRANTED")
            log.appendLine("  → Requesting permission now — grant it in the Shizuku dialog.")
            try { Shizuku.requestPermission(200) } catch (e: Exception) {
                log.appendLine("  requestPermission failed: ${e.message}")
                Log.e(TAG, "requestPermission failed", e)
            }
            return false
        }
        log.appendLine("  Permission: granted")
        Log.d(TAG, "Shizuku OK")
        return true
    }

    /**
     * Shrink the top freeform task to a 160×160 dp bubble near the right edge.
     * Full log is appended to [log]. Returns a one-line summary for Toasts.
     */
    fun shrink(context: Context, log: StringBuilder): String {
        log.appendLine("=== DOCK TEST: SHRINK ===")

        if (!checkShizuku(log)) {
            return "Shizuku not ready"
        }

        val task = findTopFreeformTask(log)
        if (task == null) {
            val msg = "No freeform task found – open an app in freeform first"
            log.appendLine(msg)
            Log.e(TAG, msg)
            return msg
        }
        log.appendLine("Found freeform task: taskId=${task.taskId}  bounds=${task.bounds}")

        saveState(context, task.taskId, task.bounds)

        val dm = context.resources.displayMetrics
        val density = dm.density
        val sizePx = (160 * density).toInt()
        val marginPx = (8 * density).toInt()
        val target = Rect(
            dm.widthPixels - sizePx - marginPx,
            (dm.heightPixels / 2) - (sizePx / 2),
            dm.widthPixels - marginPx,
            (dm.heightPixels / 2) + (sizePx / 2)
        )
        log.appendLine("Target bounds: $target  (${sizePx}×${sizePx} px)")

        val result = tryResizeMethods(task.taskId, target, log)
        val summary = if (result.first) "Dock shrink: ${result.second}" else "Dock shrink FAILED – ${result.second}"
        log.appendLine(summary)
        Log.d(TAG, summary)
        return summary
    }

    /**
     * Restore the previously-shrunk task to its original bounds.
     * Full log is appended to [log]. Returns a one-line summary for Toasts.
     */
    fun restore(context: Context, log: StringBuilder): String {
        log.appendLine("=== DOCK TEST: RESTORE ===")

        if (!checkShizuku(log)) {
            return "Shizuku not ready"
        }

        val state = loadState(context)
        if (state == null) {
            val msg = "Nothing to restore – shrink a task first"
            log.appendLine(msg)
            Log.e(TAG, msg)
            return msg
        }
        log.appendLine("Restoring taskId=${state.taskId}  bounds=${state.bounds}")

        val result = tryResizeMethods(state.taskId, state.bounds, log)
        val summary = if (result.first) {
            clearState(context)
            "Dock restore: ${result.second}"
        } else {
            "Dock restore FAILED – ${result.second}"
        }
        log.appendLine(summary)
        Log.d(TAG, summary)
        return summary
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun findTopFreeformTask(log: StringBuilder): TaskInfo? {
        val output = runShizukuCommand("dumpsys activity activities", log) ?: return null
        log.appendLine("dumpsys output length: ${output.length}")

        var lastTaskId: Int? = null
        var lastTaskBounds: Rect? = null
        var linesSinceTaskId = 0

        for (line in output.lines()) {
            val taskIdMatch = Regex("taskId=(\\d+)").find(line)
            if (taskIdMatch != null) {
                lastTaskId = taskIdMatch.groupValues[1].toIntOrNull()
                linesSinceTaskId = 0
                lastTaskBounds = extractBounds(line)
            } else {
                linesSinceTaskId++
            }

            if (lastTaskId != null && linesSinceTaskId < 6 && "windowingMode=5" in line) {
                val bounds = lastTaskBounds ?: extractBounds(line)
                log.appendLine("Freeform task found: taskId=$lastTaskId  bounds=$bounds")
                return TaskInfo(lastTaskId, bounds ?: Rect())
            }
        }

        log.appendLine("No freeform task found in dumpsys output")
        return null
    }

    private fun extractBounds(line: String): Rect? {
        val m = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").find(line) ?: return null
        val (l, t, r, b) = m.destructured
        return Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    private fun tryResizeMethods(taskId: Int, bounds: Rect, log: StringBuilder): Pair<Boolean, String> {
        val cmd = "${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}"

        val cmd1 = "cmd activity task resize $taskId $cmd"
        log.appendLine("Trying method 1: $cmd1")
        val out1 = runShizukuCommand(cmd1, log)
        log.appendLine("  output: $out1")
        if (out1 != null && !looksLikeError(out1)) {
            return true to "method 1 (cmd activity task resize) succeeded"
        }

        val cmd2 = "am task resize $taskId $cmd"
        log.appendLine("Trying method 2: $cmd2")
        val out2 = runShizukuCommand(cmd2, log)
        log.appendLine("  output: $out2")
        if (out2 != null && !looksLikeError(out2)) {
            return true to "method 2 (am task resize) succeeded"
        }

        log.appendLine("Trying method 3: ShizukuBinderWrapper IActivityTaskManager.resizeTask")
        try {
            val result = resizeViaHiddenApi(taskId, bounds)
            if (result) {
                return true to "method 3 (IActivityTaskManager.resizeTask) succeeded"
            }
        } catch (e: Exception) {
            log.appendLine("  Method 3 exception: ${e.message}")
            Log.e(TAG, "Method 3 exception: ${e.message}", e)
        }

        val errors = buildList {
            out1?.let { add("1: ${it.take(80)}") }
            out2?.let { add("2: ${it.take(80)}") }
            add("3: hidden API failed")
        }
        return false to "all methods failed – ${errors.joinToString("; ")}"
    }

    private fun looksLikeError(output: String): Boolean {
        val lower = output.lowercase()
        return "error" in lower ||
               "not found" in lower ||
               "exception" in lower ||
               "usage:" in lower ||
               "unknown command" in lower ||
               "can't find" in lower ||
               "no such" in lower
    }

    private fun resizeViaHiddenApi(taskId: Int, bounds: Rect): Boolean {
        val serviceName = "activity_task"
        val rawBinder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, serviceName) as android.os.IBinder

        val wrapper = ShizukuBinderWrapper(rawBinder)

        val data = android.os.Parcel.obtain()
        val reply = android.os.Parcel.obtain()
        try {
            data.writeInterfaceToken("android.app.IActivityTaskManager")
            data.writeInt(taskId)
            data.writeParcelable(bounds, 0)

            val code = findResizeTaskCode()
            Log.d(TAG, "resizeTask transaction code: $code")

            val success = wrapper.transact(code, data, reply, 0)
            reply.readException()
            Log.d(TAG, "transact returned: $success")
            return success
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun findResizeTaskCode(): Int {
        val defaultCode = 19
        return try {
            val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            val field = stubClass.getDeclaredField("TRANSACTION_resizeTask")
            field.isAccessible = true
            field.getInt(null)
        } catch (e: Exception) {
            Log.d(TAG, "Could not read TRANSACTION_resizeTask field, using default $defaultCode")
            defaultCode
        }
    }

    private fun runShizukuCommand(command: String, log: StringBuilder): String? {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            log.appendLine("  shell exit=$exitCode")
            if (stderr.isNotBlank()) log.appendLine("  stderr: ${stderr.take(200)}")
            if (exitCode == 0) stdout else "$stdout\nstderr: $stderr"
        } catch (e: Exception) {
            log.appendLine("  Shizuku command failed: ${e.message}")
            Log.e(TAG, "Shizuku command failed: $command", e)
            null
        }
    }

    // ── prefs helpers (context needed for SharedPreferences) ──────────────

    private fun saveState(context: Context, taskId: Int, bounds: Rect) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_TASK_ID, taskId)
            .putInt(KEY_LEFT, bounds.left)
            .putInt(KEY_TOP, bounds.top)
            .putInt(KEY_RIGHT, bounds.right)
            .putInt(KEY_BOTTOM, bounds.bottom)
            .apply()
    }

    private fun loadState(context: Context): TaskInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val taskId = prefs.getInt(KEY_TASK_ID, -1)
        if (taskId == -1) return null
        val bounds = Rect(
            prefs.getInt(KEY_LEFT, 0),
            prefs.getInt(KEY_TOP, 0),
            prefs.getInt(KEY_RIGHT, 0),
            prefs.getInt(KEY_BOTTOM, 0)
        )
        return TaskInfo(taskId, bounds)
    }

    private fun clearState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private data class TaskInfo(val taskId: Int, val bounds: Rect)
}
