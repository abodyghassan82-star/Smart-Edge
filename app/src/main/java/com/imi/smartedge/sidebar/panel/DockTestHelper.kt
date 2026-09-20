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

    /**
     * Dump task info from dumpsys activity activities.
     *
     * [searchTerm] is matched case-insensitively against every line; matching lines
     * are shown with 3 lines of surrounding context (capped at 60 lines).
     *
     * Appends the formatted dump to [log].  Returns a one-line summary for Toasts.
     */
    fun dumpTasks(log: StringBuilder, searchTerm: String = "youtube"): String {
        log.appendLine("=== DUMP TASKS ===")

        if (!checkShizuku(log)) {
            return "Shizuku not ready"
        }

        val output = runShizukuCommand("dumpsys activity activities", log)
        if (output == null) {
            log.appendLine("dumpsys returned null")
            return "dumpsys failed"
        }
        log.appendLine("dumpsys output length: ${output.length}")

        val lines = output.lines()
        val totalLines = lines.size

        // ── (a) Task{...} blocks: show each Task{ line + next 6 lines ──
        log.appendLine("\n── Task blocks (max 40) ──")
        var taskBlockCount = 0
        val taskStarts = mutableListOf<Int>()
        for ((i, line) in lines.withIndex()) {
            if (line.contains("Task{")) {
                taskStarts.add(i)
            }
        }
        for (start in taskStarts) {
            if (taskBlockCount >= 40) {
                log.appendLine("  ... (${taskStarts.size - taskBlockCount} more task blocks truncated)")
                break
            }
            val end = minOf(start + 7, totalLines)
            for (j in start until end) {
                log.appendLine("  ${lines[j]}")
            }
            log.appendLine("  ---")
            taskBlockCount++
        }
        if (taskStarts.isEmpty()) {
            log.appendLine("  (no lines containing 'Task{' found)")
        } else {
            log.appendLine("  Total Task{ lines found: ${taskStarts.size}, shown: $taskBlockCount")
        }

        // ── (b) distinct windowing-mode tokens with counts ──
        log.appendLine("\n── Windowing mode tokens ──")
        val modePattern = Regex("(?i)(windowingMode|mWindowingMode|winMode|mode)=(\\S+)")
        val modeCounts = mutableMapOf<String, Int>()
        for (line in lines) {
            for (m in modePattern.findAll(line)) {
                val key = "${m.groupValues[1]}=${m.groupValues[2]}"
                modeCounts[key] = (modeCounts[key] ?: 0) + 1
            }
        }
        if (modeCounts.isEmpty()) {
            log.appendLine("  (none found)")
        } else {
            for ((key, count) in modeCounts.entries.sortedByDescending { it.value }) {
                log.appendLine("  $key  ×$count")
            }
        }

        // ── (c) search term with 3-line context (capped at 60 lines) ──
        log.appendLine("\n── Search: \"${searchTerm}\" (3-line context, max 60 lines) ──")
        val lowerSearch = searchTerm.lowercase()
        val matchedIndices = mutableSetOf<Int>()
        for ((i, line) in lines.withIndex()) {
            if (line.lowercase().contains(lowerSearch)) {
                for (j in maxOf(0, i - 3) until minOf(totalLines, i + 4)) {
                    matchedIndices.add(j)
                }
            }
        }
        if (matchedIndices.isEmpty()) {
            log.appendLine("  (no matches)")
        } else {
            val sorted = matchedIndices.sorted()
            val shown = sorted.take(60)
            var prevIdx = -1
            for (idx in shown) {
                if (prevIdx >= 0 && idx > prevIdx + 1) {
                    log.appendLine("  ...")
                }
                log.appendLine("  L${idx}: ${lines[idx]}")
                prevIdx = idx
            }
            if (sorted.size > 60) {
                log.appendLine("  ... (${sorted.size - 60} more lines truncated)")
            }
            log.appendLine("  Total matching lines: ${matchedIndices.size}")
        }

        // ── cap total at ~200 lines ──
        val allOutput = log.toString()
        val allLines = allOutput.lines()
        if (allLines.size > 200) {
            log.clear()
            for (i in 0 until 195) {
                log.appendLine(allLines[i])
            }
            log.appendLine("...")
            log.appendLine("(truncated — ${allLines.size - 195} lines dropped)")
        }

        val summary = "Dump complete — ${totalLines} raw lines, " +
            "${taskStarts.size} Task{ blocks, " +
            "${modeCounts.size} distinct mode tokens, " +
            "${matchedIndices.size} lines matching \"$searchTerm\""
        log.appendLine("\n$summary")
        Log.d(TAG, summary)
        return summary
    }

    // ── internals ──────────────────────────────────────────────────────────

    // Case-insensitive patterns for freeform detection
    private val FREEFORM_MODE_PATTERNS = listOf(
        Regex("(?i)(?:windowingMode|mWindowingMode|winMode|mode)=freeform"),
        Regex("(?i)(?:windowingMode|mWindowingMode|winMode|mode)=5\\b")
    )

    // Task ID patterns: "taskId=123" or "#123" inside a Task{ line
    private val TASK_ID_PATTERNS = listOf(
        Regex("taskId=(\\d+)"),
        Regex("#(\\d+)")
    )

    // Bounds patterns: "Rect(l, t, r, b)" or "[l,t][r,b]" (digits may have commas)
    private val BOUNDS_PATTERNS = listOf(
        Regex("Rect\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)"),
        Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")
    )

    private fun findTopFreeformTask(log: StringBuilder): TaskInfo? {
        val output = runShizukuCommand("dumpsys activity activities", log) ?: return null
        log.appendLine("dumpsys output length: ${output.length}")

        val lines = output.lines()
        val totalLines = lines.size

        // First pass: find all Task{ blocks.  A block starts at a line containing
        // "Task{" and ends at the next "Task{" or end-of-output.  Within each block
        // we look for a task ID and freeform mode.
        data class TaskBlock(val startLine: Int, val taskId: Int?, val isFreeform: Boolean, val bounds: Rect?, val matchDetail: String)

        val blocks = mutableListOf<TaskBlock>()
        val taskStarts = mutableListOf<Int>()
        for ((i, line) in lines.withIndex()) {
            if (line.contains("Task{")) {
                taskStarts.add(i)
            }
        }

        if (taskStarts.isEmpty()) {
            // No Task{ lines at all — try flat scan as last resort
            log.appendLine("No 'Task{' lines found, scanning flat...")
            return flatScan(lines, log)
        }

        log.appendLine("Found ${taskStarts.size} Task{ blocks")

        for (blockIdx in taskStarts.indices) {
            val start = taskStarts[blockIdx]
            val end = if (blockIdx + 1 < taskStarts.size) taskStarts[blockIdx + 1] else totalLines

            var taskId: Int? = null
            var isFreeform = false
            var bounds: Rect? = null
            var matchDetail = ""

            for (i in start until end) {
                val line = lines[i]

                // Extract task ID if not yet found
                if (taskId == null) {
                    for (pat in TASK_ID_PATTERNS) {
                        val m = pat.find(line)
                        if (m != null) {
                            taskId = m.groupValues[1].toIntOrNull()
                            if (taskId != null) break
                        }
                    }
                }

                // Check freeform
                if (!isFreeform) {
                    for (pat in FREEFORM_MODE_PATTERNS) {
                        val m = pat.find(line)
                        if (m != null) {
                            isFreeform = true
                            matchDetail = "matched \"${m.value}\" on line ${i + 1}"
                            break
                        }
                    }
                }

                // Extract bounds if not yet found
                if (bounds == null) {
                    for (pat in BOUNDS_PATTERNS) {
                        val m = pat.find(line)
                        if (m != null) {
                            val l = m.groupValues[1].toIntOrNull() ?: 0
                            val t = m.groupValues[2].toIntOrNull() ?: 0
                            val r = m.groupValues[3].toIntOrNull() ?: 0
                            val b = m.groupValues[4].toIntOrNull() ?: 0
                            bounds = Rect(l, t, r, b)
                            break
                        }
                    }
                }
            }

            blocks.add(TaskBlock(start, taskId, isFreeform, bounds, matchDetail))
        }

        // Find the last freeform block (topmost = most recent)
        val freeformBlocks = blocks.filter { it.isFreeform }
        if (freeformBlocks.isEmpty()) {
            log.appendLine("No freeform task found among ${blocks.size} Task{ blocks")
            // Log what we did find for debugging
            for (b in blocks.takeLast(5)) {
                log.appendLine("  Task block at L${b.startLine}: taskId=${b.taskId} freeform=false")
            }
            return null
        }

        val best = freeformBlocks.last()
        log.appendLine("Freeform task found: taskId=${best.taskId}  bounds=${best.bounds}")
        log.appendLine("  ${best.matchDetail}")
        return TaskInfo(best.taskId ?: 0, best.bounds ?: Rect())
    }

    /**
     * Last-resort flat scan when no Task{ lines exist.
     * Searches the entire output for freeform tokens + nearby task IDs.
     */
    private fun flatScan(lines: List<String>, log: StringBuilder): TaskInfo? {
        log.appendLine("Flat scan: searching all ${lines.size} lines for freeform tokens...")
        var lastTaskId: Int? = null
        var lastTaskBounds: Rect? = null
        var linesSinceTaskId = 0
        var scannedFreeformLines = 0

        for ((i, line) in lines.withIndex()) {
            // Track task IDs
            var foundId = false
            for (pat in TASK_ID_PATTERNS) {
                val m = pat.find(line)
                if (m != null) {
                    lastTaskId = m.groupValues[1].toIntOrNull()
                    linesSinceTaskId = 0
                    foundId = true
                    break
                }
            }
            if (!foundId) {
                linesSinceTaskId++
            }

            // Track bounds
            for (pat in BOUNDS_PATTERNS) {
                val m = pat.find(line)
                if (m != null) {
                    val l = m.groupValues[1].toIntOrNull() ?: 0
                    val t = m.groupValues[2].toIntOrNull() ?: 0
                    val r = m.groupValues[3].toIntOrNull() ?: 0
                    val b = m.groupValues[4].toIntOrNull() ?: 0
                    lastTaskBounds = Rect(l, t, r, b)
                    break
                }
            }

            // Check freeform
            for (pat in FREEFORM_MODE_PATTERNS) {
                if (pat.containsMatchIn(line)) {
                    scannedFreeformLines++
                    if (lastTaskId != null && linesSinceTaskId < 10) {
                        val bounds = lastTaskBounds ?: Rect()
                        log.appendLine("  Flat scan: taskId=$lastTaskId  bounds=$bounds  (freeform at line ${i + 1})")
                        return TaskInfo(lastTaskId, bounds)
                    }
                }
            }
        }

        log.appendLine("  Flat scan: found $scannedFreeformLines freeform tokens, but no task ID within 10 lines")
        return null
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
