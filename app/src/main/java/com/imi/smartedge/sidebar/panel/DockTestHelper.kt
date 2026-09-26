package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.io.File

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
     * [targetPackage] optionally restricts selection to tasks of that package
     * (e.g. "youtube"); falls back to any on-screen freeform task when nothing
     * matches. Full log is appended to [log]. Returns a one-line summary for Toasts.
     */
    fun shrink(context: Context, log: StringBuilder, targetPackage: String? = null): String {
        log.appendLine("=== DOCK TEST: SHRINK ===")

        if (!checkShizuku(log)) {
            return "Shizuku not ready"
        }

        val task = findTopFreeformTask(context, log, targetPackage)
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
        val summary = if (result.first) {
            val onTop = keepTaskOnTop(task.taskId, log)
            "Dock shrink: ${result.second} | $onTop"
        } else {
            "Dock shrink FAILED – ${result.second}"
        }
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
            val onTop = keepTaskOnTop(state.taskId, log)
            "Dock restore: ${result.second} | $onTop"
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

    /**
     * Remove every freeform task whose package matches [targetPackage] except
     * the topmost one (first in "Application tokens in top down Z order").
     * Removal uses Shizuku shell `am task remove` / `cmd activity remove-task`
     * (whichever exists on this ROM — determined on the first attempt).
     * Full log is appended to [log]. Returns a one-line summary for Toasts.
     */
    fun closeOrphanFreeformTasks(log: StringBuilder, targetPackage: String = "youtube"): String {
        log.appendLine("=== CLOSE ORPHAN FREEFORM TASKS ===")
        log.appendLine("Target package filter: $targetPackage")

        if (!checkShizuku(log)) {
            return "Shizuku not ready"
        }

        val output = runShizukuCommand("dumpsys activity activities", log)
        if (output == null) {
            log.appendLine("dumpsys returned null")
            return "dumpsys failed"
        }
        val lines = output.lines()

        val blocks = parseTaskBlocks(lines)
        if (blocks.isEmpty()) {
            log.appendLine("No Task{ blocks found in dump")
            return "No tasks found"
        }

        val freeformAll = blocks.filter { it.isFreeform && it.taskId != null }
        log.appendLine("Freeform tasks in dump: ${freeformAll.size}")

        fun matchesTarget(b: TaskBlock): Boolean =
            b.pkg?.contains(targetPackage, ignoreCase = true) == true ||
                b.blockText.contains(targetPackage, ignoreCase = true)

        val matched = freeformAll.filter { matchesTarget(it) }
        if (matched.isEmpty()) {
            log.appendLine("No freeform tasks match \"$targetPackage\"")
            for (b in freeformAll) {
                log.appendLine("  taskId=${b.taskId} pkg=${b.pkg ?: "?"} bounds=${b.bounds ?: "?"}")
            }
            return "No freeform tasks for \"$targetPackage\""
        }

        val zOrder = parseZOrderTaskIds(lines, log)
        for (b in matched) {
            val zi = b.taskId?.let { zOrder.indexOf(it) } ?: -1
            val zText = if (zi >= 0) "zOrder=#$zi" else "zOrder=not-listed"
            log.appendLine("matched: taskId=${b.taskId} pkg=${b.pkg ?: "?"} bounds=${b.bounds ?: "?"} $zText")
        }

        val top = pickTopmost(matched, zOrder)
        val keptId = top?.taskId
        if (keptId == null) {
            log.appendLine("Could not determine topmost task – aborting")
            return "Could not determine topmost task"
        }
        val others = matched.filter { it.taskId != keptId }
        log.appendLine("Keeping topmost taskId=$keptId; ${others.size} orphan(s) to remove")
        if (others.isEmpty()) {
            val msg = "No orphans – only topmost freeform task $keptId exists"
            log.appendLine(msg)
            return msg
        }

        // Learned on the first attempt: whichever remove command actually
        // exists on this ROM is tried first for the remaining tasks.
        var preferCmdActivity = false

        fun attemptRemove(id: Int): Boolean {
            val commands = if (preferCmdActivity) {
                listOf("cmd activity remove-task $id", "am task remove $id")
            } else {
                listOf("am task remove $id", "cmd activity remove-task $id")
            }
            for (cmd in commands) {
                log.appendLine("  trying: $cmd")
                val out = runShizukuCommand(cmd, log)
                log.appendLine("    output: ${out?.trim()?.take(300) ?: "null"}")
                if (out != null && !looksLikeError(out)) {
                    preferCmdActivity = cmd.startsWith("cmd activity")
                    log.appendLine("    → $cmd succeeded")
                    return true
                }
                log.appendLine("    → $cmd failed")
            }
            log.appendLine("    → both remove commands failed for taskId=$id")
            return false
        }

        var removed = 0
        var failed = 0
        for (b in others) {
            val id = b.taskId ?: continue
            log.appendLine("Removing orphan taskId=$id pkg=${b.pkg ?: "?"} bounds=${b.bounds ?: "?"}")
            if (attemptRemove(id)) removed++ else failed++
        }

        // Verify with a fresh dump
        log.appendLine("Verifying after removal...")
        val output2 = runShizukuCommand("dumpsys activity activities", log)
        if (output2 != null) {
            val remaining = parseTaskBlocks(output2.lines()).filter {
                it.isFreeform && it.taskId != null && matchesTarget(it)
            }
            val rem = remaining.joinToString(", ") { "taskId=${it.taskId}" }
            log.appendLine("Remaining freeform \"$targetPackage\" tasks: ${if (rem.isEmpty()) "(none)" else rem}")
        }

        val summary = "Closed $removed orphan freeform task(s), $failed failed – kept topmost taskId=$keptId"
        log.appendLine(summary)
        Log.d(TAG, summary)
        return summary
    }

    // ── dock-to-bubble engine API (Phase B) ────────────────────────────────

    /** A freeform task selected for docking. */
    data class DockTask(val taskId: Int, val bounds: Rect, val pkg: String?)

    /** Result of a dock (shrink-to-bubble) operation. */
    data class DockResult(
        val taskId: Int,
        val originalBounds: Rect,
        val bubbleBounds: Rect,
        val summary: String,
        val success: Boolean
    )

    /**
     * Quiet, strict lookup: the HIGHEST freeform task whose dump block
     * mentions [targetPackage] (no fallback to unrelated packages, no log
     * output, no Shizuku permission prompt).  Off-screen orphans are skipped.
     * Returns null when no matching freeform task exists or the dump fails.
     * Runs shell commands — call from a background thread.
     */
    fun findTaskByPackage(context: Context, targetPackage: String): DockTask? {
        val quiet = StringBuilder()
        val output = runShizukuCommand("dumpsys activity activities", quiet) ?: return null
        val dm = context.resources.displayMetrics
        val match = parseTaskBlocks(output.lines())
            .filter { it.isFreeform && it.taskId != null && matchesPackage(it, targetPackage) }
            .filter { b ->
                val bounds = b.bounds
                bounds == null || !isMostlyOffScreen(bounds, dm.widthPixels, dm.heightPixels)
            }
            .maxByOrNull { it.taskId ?: -1 } ?: return null
        val id = match.taskId ?: return null
        return DockTask(id, match.bounds ?: Rect(), match.pkg)
    }

    /**
     * Shrink a KNOWN task ([taskId]) to the bubble target and save
     * [originalBounds] as the restorable state.  Used by the dock overlay
     * flow.  Full log is appended to [log].  Runs shell commands — call from
     * a background thread.
     */
    fun dockTask(context: Context, taskId: Int, originalBounds: Rect, log: StringBuilder): DockResult {
        log.appendLine("=== DOCK TO BUBBLE: taskId=$taskId ===")
        if (!checkShizuku(log)) {
            return DockResult(taskId, Rect(originalBounds), Rect(), "Shizuku not ready", false)
        }
        saveState(context, taskId, originalBounds)
        val bubble = computeBubbleBounds(context)
        log.appendLine("originalBounds=$originalBounds  bubbleBounds=$bubble")
        val result = tryResizeMethods(taskId, bubble, log)
        val summary = if (result.first) {
            val onTop = keepTaskOnTop(taskId, log)
            "Dock: task $taskId shrunk to bubble | $onTop"
        } else {
            "Dock FAILED – ${result.second}"
        }
        log.appendLine(summary)
        Log.d(TAG, summary)
        return DockResult(taskId, Rect(originalBounds), Rect(bubble), summary, result.first)
    }

    /**
     * Restore a task to [bounds] and raise it.  Clears the saved dock state
     * when it points at the same task.  Returns a one-line summary; a
     * summary containing "FAILED" means the resize did not go through.
     * Runs shell commands — call from a background thread.
     */
    fun restoreTo(context: Context, taskId: Int, bounds: Rect, log: StringBuilder): String {
        log.appendLine("=== RESTORE TO BOUNDS: taskId=$taskId → $bounds ===")
        if (!checkShizuku(log)) return "Shizuku not ready"
        val summary = resizeAndFocus(taskId, bounds, log, "restore")
        if (!summary.contains("FAILED")) {
            val state = loadState(context)
            if (state != null && state.taskId == taskId) clearState(context)
        }
        return summary
    }

    /**
     * Resize a task to fullscreen and raise it.  Returns a one-line summary.
     * Runs shell commands — call from a background thread.
     */
    fun expandTask(context: Context, taskId: Int, log: StringBuilder): String {
        log.appendLine("=== EXPAND TASK: taskId=$taskId ===")
        if (!checkShizuku(log)) return "Shizuku not ready"
        val dm = context.resources.displayMetrics
        val bounds = Rect(0, 0, dm.widthPixels, dm.heightPixels)
        return resizeAndFocus(taskId, bounds, log, "expand")
    }

    /**
     * Move/resize a task to [bounds] without raising it (used by the header
     * drag).  Returns a one-line summary.  Runs shell commands — call from a
     * background thread.
     */
    fun moveTask(taskId: Int, bounds: Rect, log: StringBuilder): String {
        log.appendLine("=== MOVE TASK: taskId=$taskId → $bounds ===")
        if (!checkShizuku(log)) return "Shizuku not ready"
        return resizeAndFocus(taskId, bounds, log, "move", focus = false)
    }

    /**
     * Close (remove) a task via `am task remove` / `cmd activity remove-task`.
     * Returns a one-line summary; "FAILED" means neither command worked.
     * Runs shell commands — call from a background thread.
     */
    fun closeTask(taskId: Int, log: StringBuilder): String {
        log.appendLine("=== CLOSE TASK: taskId=$taskId ===")
        if (!checkShizuku(log)) return "Shizuku not ready"
        for (cmd in listOf("am task remove $taskId", "cmd activity remove-task $taskId")) {
            log.appendLine("Trying: $cmd")
            val out = runShizukuCommand(cmd, log)
            log.appendLine("  output: ${out?.trim()?.take(300) ?: "null"}")
            if (out != null && !looksLikeError(out)) {
                val summary = "Closed task $taskId via $cmd"
                log.appendLine(summary)
                Log.d(TAG, summary)
                return summary
            }
        }
        val summary = "FAILED to close task $taskId (both remove commands failed)"
        log.appendLine(summary)
        Log.e(TAG, summary)
        return summary
    }

    /**
     * Quiet bounds probe for the header/bubble follow loop.  Returns the
     * task's current bounds, an EMPTY Rect when the task exists but its
     * bounds could not be parsed, or null when the task is gone / the dump
     * failed (caller should require several consecutive nulls before
     * treating the task as closed).  Runs shell commands — call from a
     * background thread.
     */
    fun queryTaskBounds(taskId: Int): Rect? {
        val quiet = StringBuilder()
        val output = runShizukuCommand("dumpsys activity activities", quiet) ?: return null
        val block = parseTaskBlocks(output.lines()).firstOrNull { it.taskId == taskId } ?: return null
        return block.bounds ?: Rect()
    }

    /** The bubble shrink target: 160×160 dp near the right edge (matches shrink()). */
    fun computeBubbleBounds(context: Context): Rect {
        val dm = context.resources.displayMetrics
        val density = dm.density
        val sizePx = (160 * density).toInt()
        val marginPx = (8 * density).toInt()
        return Rect(
            dm.widthPixels - sizePx - marginPx,
            (dm.heightPixels / 2) - (sizePx / 2),
            dm.widthPixels - marginPx,
            (dm.heightPixels / 2) + (sizePx / 2)
        )
    }

    private fun resizeAndFocus(
        taskId: Int,
        bounds: Rect,
        log: StringBuilder,
        label: String,
        focus: Boolean = true
    ): String {
        val result = tryResizeMethods(taskId, bounds, log)
        val summary = if (result.first) {
            if (focus) {
                val onTop = keepTaskOnTop(taskId, log)
                "$label: task $taskId → $bounds | $onTop"
            } else {
                "$label: task $taskId → $bounds | ${result.second}"
            }
        } else {
            "$label FAILED – ${result.second}"
        }
        log.appendLine(summary)
        Log.d(TAG, summary)
        return summary
    }

    // ── persistent file log (header / dock flows) ──────────────────────────

    private const val LOG_FILE_NAME = "docklog.txt"
    private const val LOG_FILE_MAX_BYTES = 500_000
    private const val SHARED_LOG_MAX_CHARS = 100_000
    private val fileLogLock = Any()
    private val sharedLog = StringBuilder()

    /** UI hook: MainActivity sets this so fileLog lines reach the Dock Test log view. */
    @Volatile
    var logListener: ((String) -> Unit)? = null

    /** Snapshot of every fileLog line kept in memory this session (seeds the UI). */
    fun sharedLogText(): String = synchronized(sharedLog) { sharedLog.toString() }

    /**
     * Airtight step logger: appends a timestamped line to
     * `filesDir/docklog.txt` (created if missing; oldest lines dropped once
     * the file passes ~500 KB), mirrors it into the shared in-memory buffer,
     * and forwards it to [logListener] (marshalled to the main thread) so the
     * Dock Test on-screen log + "Copy log" capture header-button activity.
     * Thread-safe and never throws.
     */
    fun fileLog(context: Context, tag: String, message: String) {
        try {
            val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            val line = "[$time][$tag] $message"

            synchronized(fileLogLock) {
                val file = File(context.filesDir, LOG_FILE_NAME)
                if (file.exists() && file.length() > LOG_FILE_MAX_BYTES) {
                    // Cap: keep only the newest lines (down to ~half the cap), rewrite.
                    val kept = ArrayList<String>()
                    var size = 0
                    for (l in file.readLines().asReversed()) {
                        val cost = l.length + 1
                        if (size + cost > LOG_FILE_MAX_BYTES / 2) break
                        kept.add(0, l)
                        size += cost
                    }
                    file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
                    Log.d(TAG, "fileLog: trimmed $LOG_FILE_NAME to ${kept.size} newest lines")
                }
                file.appendText(line + "\n")
            }

            synchronized(sharedLog) {
                sharedLog.append(line).append('\n')
                if (sharedLog.length > SHARED_LOG_MAX_CHARS) {
                    val half = SHARED_LOG_MAX_CHARS / 2
                    val cut = sharedLog.indexOf("\n", sharedLog.length - half)
                    sharedLog.delete(0, if (cut >= 0) cut + 1 else sharedLog.length - half)
                }
            }

            val listener = logListener
            if (listener != null) {
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    listener(line)
                } else {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { listener(line) }
                }
            }
            Log.d(TAG, "fileLog: $line")
        } catch (e: Exception) {
            Log.e(TAG, "fileLog FAILED (${e.javaClass.simpleName}): ${e.message}")
        }
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

    private data class TaskBlock(
        val startLine: Int,
        val taskId: Int?,
        val isFreeform: Boolean,
        val bounds: Rect?,
        val pkg: String?,
        val blockText: String,
        val matchDetail: String
    )

    private fun parseTaskBlocks(lines: List<String>): List<TaskBlock> {
        val totalLines = lines.size
        val taskStarts = mutableListOf<Int>()
        for ((i, line) in lines.withIndex()) {
            if (line.contains("Task{")) {
                taskStarts.add(i)
            }
        }

        val blocks = mutableListOf<TaskBlock>()
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

            val blockText = lines.subList(start, end).joinToString("\n")
            blocks.add(TaskBlock(start, taskId, isFreeform, bounds, extractPackage(blockText), blockText, matchDetail))
        }
        return blocks
    }

    private fun extractPackage(blockText: String): String? =
        Regex("A=\\d+:([\\w.]+)").find(blockText)?.groupValues?.get(1)
            ?: Regex("ActivityRecord\\{\\S+\\s+u\\d+\\s+([\\w.]+)/").find(blockText)?.groupValues?.get(1)

    /**
     * Parse task IDs in top-down Z order from the
     * "Application tokens in top down Z order" section of the dump.
     * The FIRST ID is the topmost / most recent task.
     */
    private fun parseZOrderTaskIds(lines: List<String>, log: StringBuilder): List<Int> {
        val headerIdx = lines.indexOfFirst { it.contains("Application tokens in top down Z order") }
        if (headerIdx < 0) {
            log.appendLine("'Application tokens in top down Z order' section not found in dump")
            return emptyList()
        }

        val ids = mutableListOf<Int>()
        for (i in (headerIdx + 1) until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val startsAtCol0 = !line.startsWith(" ") && !line.startsWith("\t")
            if (startsAtCol0 && !line.trimStart().startsWith("*")) {
                // Next top-level dumpsys section begins — stop.
                break
            }
            val m = Regex("\\bt(\\d+)\\}").find(line) ?: Regex("taskId=(\\d+)").find(line)
            val id = m?.groupValues?.get(1)?.toIntOrNull()
            if (id != null && id !in ids) ids.add(id)
        }

        if (ids.isEmpty()) {
            log.appendLine("Z-order (top→bottom): (no task IDs parsed)")
        } else {
            val shown = ids.take(30).joinToString(", ")
            val more = if (ids.size > 30) " …(${ids.size} total)" else ""
            log.appendLine("Z-order (top→bottom): $shown$more")
        }
        return ids
    }

    private fun isMostlyOffScreen(bounds: Rect, screenW: Int, screenH: Int): Boolean =
        bounds.left !in 0..screenW ||
        bounds.top !in 0..screenH ||
        bounds.right !in 0..screenW ||
        bounds.bottom !in 0..screenH

    /**
     * Pick the candidate appearing FIRST in the Z-order list (topmost/most
     * recent). Falls back to the last Task{ block (legacy behaviour) when no
     * candidate appears in the Z-order section.
     */
    private fun pickTopmost(candidates: List<TaskBlock>, zOrder: List<Int>): TaskBlock? {
        if (candidates.isEmpty()) return null
        val ranked = candidates.mapNotNull { b ->
            val id = b.taskId ?: return@mapNotNull null
            val idx = zOrder.indexOf(id)
            if (idx >= 0) idx to b else null
        }
        if (ranked.isNotEmpty()) return ranked.minByOrNull { it.first }!!.second
        return candidates.last()
    }

    private fun findTopFreeformTask(context: Context, log: StringBuilder, targetPackage: String? = null): TaskInfo? {
        val output = runShizukuCommand("dumpsys activity activities", log) ?: return null
        log.appendLine("dumpsys output length: ${output.length}")

        val lines = output.lines()
        val blocks = parseTaskBlocks(lines)

        if (blocks.isEmpty()) {
            // No Task{ lines at all — try flat scan as last resort
            log.appendLine("No 'Task{' lines found, scanning flat...")
            return flatScan(lines, log)
        }

        log.appendLine("Found ${blocks.size} Task{ blocks")

        // Display bounds: prefer init=WxH from the dump itself
        val initMatch = Regex("init=(\\d+)x(\\d+)").find(output)
        val screenW: Int
        val screenH: Int
        if (initMatch != null) {
            screenW = initMatch.groupValues[1].toInt()
            screenH = initMatch.groupValues[2].toInt()
            log.appendLine("Display size: ${screenW}x$screenH (from init=WxH)")
        } else {
            val dm = context.resources.displayMetrics
            screenW = dm.widthPixels
            screenH = dm.heightPixels
            log.appendLine("Display size: ${screenW}x$screenH (init=WxH not in dump, using displayMetrics)")
        }

        val freeformBlocks = blocks.filter { it.isFreeform && it.taskId != null }
        if (freeformBlocks.isEmpty()) {
            log.appendLine("No freeform task found among ${blocks.size} Task{ blocks")
            // Log what we did find for debugging
            for (b in blocks.takeLast(5)) {
                log.appendLine("  Task block at L${b.startLine}: taskId=${b.taskId} freeform=false")
            }
            return null
        }

        val onScreen = mutableListOf<TaskBlock>()
        for (b in freeformBlocks) {
            val bounds = b.bounds
            if (bounds != null && isMostlyOffScreen(bounds, screenW, screenH)) {
                log.appendLine("skipped (off-screen orphan): taskId=${b.taskId} bounds=$bounds pkg=${b.pkg ?: "?"}")
                continue
            }
            log.appendLine("candidate: taskId=${b.taskId} bounds=${bounds ?: "?"} pkg=${b.pkg ?: "?"}")
            onScreen.add(b)
        }

        if (onScreen.isEmpty()) {
            log.appendLine("All ${freeformBlocks.size} freeform task(s) are off-screen orphans – none usable")
            return null
        }

        // Prefer tasks of the target package; fall back to all candidates.
        val scoped = if (targetPackage != null) {
            val matching = onScreen.filter { matchesPackage(it, targetPackage) }
            if (matching.isNotEmpty()) {
                log.appendLine("Package filter \"$targetPackage\": ${matching.size} of ${onScreen.size} candidate(s) match")
                matching
            } else {
                log.appendLine("No on-screen freeform task matches \"$targetPackage\" – using all ${onScreen.size} candidate(s)")
                onScreen
            }
        } else {
            onScreen
        }

        // Highest taskId = most recently created task.  (Z-order text parsing
        // was tried first and finds nothing on this ROM, so it was dropped.)
        val best = scoped.maxByOrNull { it.taskId ?: -1 } ?: return null
        log.appendLine("Selected taskId=${best.taskId} – highest taskId among ${scoped.size} candidate(s) (most recently created)")
        log.appendLine("  ${best.matchDetail}")
        return TaskInfo(best.taskId ?: 0, best.bounds ?: Rect())
    }

    private fun matchesPackage(b: TaskBlock, targetPackage: String): Boolean =
        b.pkg?.contains(targetPackage, ignoreCase = true) == true ||
            b.blockText.contains(targetPackage, ignoreCase = true)

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
            val redraw = forceRedrawViaHiddenApi(taskId, bounds, log)
            return true to "method 1 (cmd activity task resize) succeeded; $redraw"
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

    /**
     * Follow-up after a successful shell resize: `cmd activity task resize`
     * can report success without the app visibly redrawing, so retry through
     * the ShizukuBinderWrapper hidden-API path (IActivityTaskManager.resizeTask).
     * Returns a one-line result description for the summary.
     */
    private fun forceRedrawViaHiddenApi(taskId: Int, bounds: Rect, log: StringBuilder): String {
        log.appendLine("Shell resize succeeded but may not force a redraw — retrying via hidden API")
        log.appendLine("Trying method 1b: ShizukuBinderWrapper IActivityTaskManager.resizeTask")
        return try {
            val ok = resizeViaHiddenApi(taskId, bounds)
            val msg = if (ok) {
                "method 1b (IActivityTaskManager.resizeTask) succeeded"
            } else {
                "method 1b (IActivityTaskManager.resizeTask) returned false"
            }
            log.appendLine("  $msg")
            Log.d(TAG, msg)
            msg
        } catch (e: Exception) {
            val msg = "method 1b (IActivityTaskManager.resizeTask) failed: ${e.message}"
            log.appendLine("  $msg")
            Log.e(TAG, msg, e)
            msg
        }
    }

    /**
     * After a successful resize, try to raise the task so it doesn't fall
     * behind other apps when the user taps elsewhere.
     *
     * Tries `am task focus <taskId>` first, then `cmd activity task focus
     * <taskId>` (AOSP ActivityManagerShellCommand#runTaskFocus →
     * setFocusedTask), logging the output of each.  When neither exists, the
     * `cmd activity` usage is probed for focus/top/front/always commands and
     * it is logged that a persistent always-on-top needs the spec's
     * header/bubble overlay layer instead.
     *
     * Returns a short status line for the summary.
     */
    private fun keepTaskOnTop(taskId: Int, log: StringBuilder): String {
        log.appendLine("── Keep on top: taskId=$taskId ──")

        val commands = listOf(
            "am task focus $taskId",
            "cmd activity task focus $taskId"
        )
        for (cmd in commands) {
            log.appendLine("Trying: $cmd")
            val out = runShizukuCommand(cmd, log)
            log.appendLine("  output: ${out?.trim()?.take(300) ?: "null"}")
            if (out != null && !looksLikeError(out)) {
                log.appendLine("  → WORKED: $cmd")
                return "on-top: $cmd OK"
            }
            log.appendLine("  → not supported on this ROM: $cmd")
        }

        // Nothing worked — probe what this ROM's `cmd activity` reports as
        // supported, so the log shows what actually exists here.
        log.appendLine("Probing supported commands: cmd activity (usage)")
        val usage = runShizukuCommand("cmd activity", log)
        if (usage != null) {
            val interesting = usage.lines().filter { l ->
                val s = l.lowercase().trim()
                s.isNotBlank() &&
                    ("focus" in s || "top" in s || "front" in s || "always" in s)
            }
            if (interesting.isEmpty()) {
                log.appendLine("  (usage lists no focus/top/front/always commands)")
            } else {
                for (l in interesting.take(10)) {
                    log.appendLine("  usage: ${l.trim()}")
                }
            }
        }

        val msg = "on-top: no always-on-top command on this ROM – needs the spec header/bubble overlay layer instead"
        log.appendLine(msg)
        Log.d(TAG, msg)
        return msg
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
