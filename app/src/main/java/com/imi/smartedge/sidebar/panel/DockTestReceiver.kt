package com.imi.smartedge.sidebar.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Log
import android.widget.Toast
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Debug receiver to test task resizing on Android 15 via Shizuku.
 *
 * How the task ID is found:
 *   We run "dumpsys activity activities" through Shizuku and parse the output
 *   looking for "taskId=" lines whose adjacent "windowingMode=5" indicates a
 *   freeform task.  The first match is treated as the "top freeform task".
 *   This avoids the deprecated ActivityManager.getRunningTasks() which is
 *   restricted on modern Android.
 *
 * Resize methods tried in order:
 *   1. "cmd activity task resize <taskId> <l> <t> <r> <b>"
 *   2. "am task resize <taskId> <l> <t> <r> <b>"
 *   3. ShizukuBinderWrapper → IActivityTaskManager.resizeTask (hidden API)
 *
 * Every command and its stdout/stderr is logged with tag "DockTest".
 * A Toast is shown indicating which method succeeded or why each failed.
 */
class DockTestReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DockTest"
        const val ACTION_SHRINK =
            "com.abody.smartedgedock.action.DOCK_TEST_SHRINK"
        const val ACTION_RESTORE =
            "com.abody.smartedgedock.action.DOCK_TEST_RESTORE"

        // SharedPreferences file shared with FloatingPanelService
        private const val PREFS_NAME = "dock_test_state"
        private const val KEY_TASK_ID = "task_id"
        private const val KEY_LEFT = "left"
        private const val KEY_TOP = "top"
        private const val KEY_RIGHT = "right"
        private const val KEY_BOTTOM = "bottom"
    }

    /* ── entry point ─────────────────────────────────────────────────────── */

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            ACTION_SHRINK  -> handleShrink(context)
            ACTION_RESTORE -> handleRestore(context)
        }
    }

    /* ── shrink ──────────────────────────────────────────────────────────── */

    private fun handleShrink(context: Context) {
        Log.d(TAG, "=== DOCK TEST: SHRINK ===")

        if (!checkShizuku(context)) {
            return
        }

        // 1. Find the top freeform task
        val task = findTopFreeformTask()
        if (task == null) {
            val msg = "No freeform task found – open an app in freeform first"
            Log.e(TAG, msg)
            toast(context, msg)
            return
        }
        Log.d(TAG, "Found freeform task: taskId=${task.taskId}  bounds=${task.bounds}")

        // 2. Save original bounds for later restore
        saveState(context, task.taskId, task.bounds)

        // 3. Compute target bounds: 160×160 dp near the right edge, fully on-screen
        val dm = context.resources.displayMetrics
        val density = dm.density
        val sizePx = (160 * density).toInt()
        val marginPx = (8 * density).toInt()
        val target = Rect(
            dm.widthPixels - sizePx - marginPx,   // left
            (dm.heightPixels / 2) - (sizePx / 2),  // top  (vertically centred)
            dm.widthPixels - marginPx,              // right
            (dm.heightPixels / 2) + (sizePx / 2)   // bottom
        )
        Log.d(TAG, "Target bounds: $target  (${sizePx}×${sizePx} px)")

        // 4. Try resize methods in order
        val result = tryResizeMethods(task.taskId, target)

        if (result.first) {
            toast(context, "Dock shrink: ${result.second}")
        } else {
            toast(context, "Dock shrink FAILED – ${result.second}")
        }
    }

    /* ── restore ─────────────────────────────────────────────────────────── */

    private fun handleRestore(context: Context) {
        Log.d(TAG, "=== DOCK TEST: RESTORE ===")

        if (!checkShizuku(context)) {
            return
        }

        val state = loadState(context)
        if (state == null) {
            val msg = "Nothing to restore – shrink a task first"
            Log.e(TAG, msg)
            toast(context, msg)
            return
        }
        Log.d(TAG, "Restoring taskId=${state.taskId}  bounds=${state.bounds}")

        val result = tryResizeMethods(state.taskId, state.bounds)

        if (result.first) {
            clearState(context)
            toast(context, "Dock restore: ${result.second}")
        } else {
            toast(context, "Dock restore FAILED – ${result.second}")
        }
    }

    /* ── find top freeform task ──────────────────────────────────────────── */

    /**
     * Parses "dumpsys activity activities" looking for a freeform (windowingMode=5)
     * task.  The dump format is roughly:
     *
     *   Task{... taskId=123 ...}
     *     ...
     *     mWindowingMode=5
     *
     * We scan line-by-line; when we see "taskId=<N>" we remember it, and when
     * we subsequently see "mWindowingMode=5" within a few lines we report it.
     */
    private fun findTopFreeformTask(): TaskInfo? {
        val output = runShizukuCommand("dumpsys activity activities") ?: return null
        Log.d(TAG, "dumpsys output length: ${output.length}")

        var lastTaskId: Int? = null
        var lastTaskBounds: Rect? = null
        var linesSinceTaskId = 0

        for (line in output.lines()) {
            // Capture taskId
            val taskIdMatch = Regex("taskId=(\\d+)").find(line)
            if (taskIdMatch != null) {
                lastTaskId = taskIdMatch.groupValues[1].toIntOrNull()
                linesSinceTaskId = 0

                // Try to grab bounds from the same line: [left,top][right,bottom]
                lastTaskBounds = extractBounds(line)
            } else {
                linesSinceTaskId++
            }

            // If we see windowingMode=5 within a few lines of a taskId, it's freeform
            if (lastTaskId != null && linesSinceTaskId < 6 && "windowingMode=5" in line) {
                // If bounds weren't on the taskId line, try this line
                val bounds = lastTaskBounds ?: extractBounds(line)
                Log.d(TAG, "Freeform task found: taskId=$lastTaskId  bounds=$bounds")
                return TaskInfo(lastTaskId, bounds ?: Rect())
            }
        }

        Log.d(TAG, "No freeform task found in dumpsys output")
        return null
    }

    /** Extract [left,top][right,bottom] from a dumpsys line. */
    private fun extractBounds(line: String): Rect? {
        val m = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").find(line) ?: return null
        val (l, t, r, b) = m.destructured
        return Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    /* ── resize with fallback ────────────────────────────────────────────── */

    /**
     * Tries three resize methods in order. Returns (success, description).
     */
    private fun tryResizeMethods(taskId: Int, bounds: Rect): Pair<Boolean, String> {
        val cmd = "${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}"

        // Method 1: "cmd activity task resize"
        val cmd1 = "cmd activity task resize $taskId $cmd"
        Log.d(TAG, "Trying method 1: $cmd1")
        val out1 = runShizukuCommand(cmd1)
        Log.d(TAG, "Method 1 output: $out1")
        if (out1 != null && !looksLikeError(out1)) {
            return true to "method 1 (cmd activity task resize) succeeded"
        }

        // Method 2: "am task resize"
        val cmd2 = "am task resize $taskId $cmd"
        Log.d(TAG, "Trying method 2: $cmd2")
        val out2 = runShizukuCommand(cmd2)
        Log.d(TAG, "Method 2 output: $out2")
        if (out2 != null && !looksLikeError(out2)) {
            return true to "method 2 (am task resize) succeeded"
        }

        // Method 3: ShizukuBinderWrapper → IActivityTaskManager.resizeTask
        Log.d(TAG, "Trying method 3: ShizukuBinderWrapper IActivityTaskManager.resizeTask")
        try {
            val result = resizeViaHiddenApi(taskId, bounds)
            if (result) {
                return true to "method 3 (IActivityTaskManager.resizeTask) succeeded"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Method 3 exception: ${e.message}", e)
        }

        val errors = buildList {
            out1?.let { add("1: ${it.take(80)}") }
            out2?.let { add("2: ${it.take(80)}") }
            add("3: hidden API failed")
        }
        return false to "all methods failed – ${errors.joinToString("; ")}"
    }

    /** Heuristic: output contains common error indicators. */
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

    /* ── hidden-API resize via Shizuku BinderWrapper ─────────────────────── */

    /**
     * Uses ShizukuBinderWrapper to call the hidden IActivityTaskManager.resizeTask().
     *
     * Flow:
     *   1. Get the ActivityTaskManager binder via ServiceManager.getService("activity_task")
     *   2. Wrap it with ShizukuBinderWrapper
     *   3. Create a Parcel with the task ID and target Rect
     *   4. transact(TRANSACTION_resizeTask, ...)
     *
     * This mirrors what Shell does internally and avoids having to find the
     * right AIDL class.
     */
    private fun resizeViaHiddenApi(taskId: Int, bounds: Rect): Boolean {
        // Get the raw IBinder for the activity_task service
        val serviceName = "activity_task"
        val rawBinder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, serviceName) as android.os.IBinder

        // Wrap it so transact calls go through Shizuku (bypassing permission checks)
        val wrapper = ShizukuBinderWrapper(rawBinder)

        // Build the Parcel manually – same format as IActivityTaskManager.resizeTask(int, Rect)
        val data = android.os.Parcel.obtain()
        val reply = android.os.Parcel.obtain()
        try {
            data.writeInterfaceToken("android.app.IActivityTaskManager")
            data.writeInt(taskId)
            // writeParcelable works on all API levels; writeTypedValue needs API 29+
            data.writeParcelable(bounds, 0)

            // TRANSACTION_resizeTask is typically 19 or varies by API level.
            // We use a safe approach: try the numeric constant, fall back to
            // scanning declared fields.
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

    /**
     * Attempt to discover the transaction code for resizeTask.
     * AOSP typically defines it as the 19th method in IActivityTaskManager,
     * but we scan the AIDL stub's fields to find the right one.
     */
    private fun findResizeTaskCode(): Int {
        // Hardcoded fallback for Android 12-15 (widely reported as 19)
        val defaultCode = 19
        return try {
            val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            // Look for a static int field named "TRANSACTION_resizeTask"
            val field = stubClass.getDeclaredField("TRANSACTION_resizeTask")
            field.isAccessible = true
            field.getInt(null)
        } catch (e: Exception) {
            Log.d(TAG, "Could not read TRANSACTION_resizeTask field, using default $defaultCode")
            defaultCode
        }
    }

    /* ── Shizuku helpers ─────────────────────────────────────────────────── */

    private fun checkShizuku(context: Context): Boolean {
        // 1. Is the Shizuku service running?
        val running = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
        if (!running) {
            toast(context, "Shizuku is not running. Start the Shizuku app first.")
            return false
        }

        // 2. Do we have permission?
        val perm = try {
            Shizuku.checkSelfPermission()
        } catch (e: Exception) {
            PackageManager.PERMISSION_DENIED
        }
        if (perm != PackageManager.PERMISSION_GRANTED) {
            toast(context, "Shizuku permission not granted. Grant it when prompted.")
            try {
                Shizuku.requestPermission(100)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request Shizuku permission", e)
                toast(context, "Cannot request Shizuku permission — open Smart Edge Dock and try again")
            }
            return false
        }

        return true
    }

    private fun runShizukuCommand(command: String): String? {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            Log.d(TAG, "Shell: $command  → exit=$exitCode")
            if (stderr.isNotBlank()) Log.d(TAG, "  stderr: $stderr")
            if (exitCode == 0) stdout else "$stdout\nstderr: $stderr"
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku command failed: $command", e)
            null
        }
    }

    /* ── prefs helpers ───────────────────────────────────────────────────── */

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

    private fun toast(context: Context, msg: String) {
        Log.d(TAG, "Toast: $msg")
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    /* ── data class ──────────────────────────────────────────────────────── */

    private data class TaskInfo(val taskId: Int, val bounds: Rect)
}
