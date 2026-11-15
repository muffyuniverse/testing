package ai.pechka.factory

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.EditText
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PechkaDebugProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return true
        installCrashHook(ctx)
        registerFactory()
        (ctx as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { currentActivity = WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivity.get() === activity) currentActivity = WeakReference(null)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (callingPackage != ALLOWED_CALLER) {
            throw SecurityException("Caller is not authorized for Pechka debug RPC.")
        }
        if (method != "pechka.rpc") {
            return errorReply(null, -32601, "Unsupported provider call method.")
        }
        val req = runCatching { JSONObject(arg.orEmpty()) }.getOrNull()
            ?: return errorReply(null, -32700, "Invalid JSON-RPC payload.")
        val id = req.opt("id")
        val rpcMethod = req.optString("method").trim()
        val params = req.optJSONObject("params") ?: JSONObject()
        val replyFd: ParcelFileDescriptor? = extras?.getParcelable("reply_fd")
        val payloadFd: ParcelFileDescriptor? = extras?.getParcelable("payload_fd")
        val outBundle = Bundle()
        return try {
            val result = dispatch(rpcMethod, params, payloadFd, replyFd, outBundle)
            val envelope = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)
            outBundle.putString("json", envelope.toString())
            outBundle
        } catch (t: Throwable) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            errorReply(id, -32000, t.message ?: t.javaClass.simpleName, sw.toString().take(8000))
        } finally {
            runCatching { replyFd?.close() }
            runCatching { payloadFd?.close() }
        }
    }

    private fun dispatch(
        method: String,
        params: JSONObject,
        payloadFd: ParcelFileDescriptor?,
        replyFd: ParcelFileDescriptor?,
        outBundle: Bundle,
    ): Any = when (method) {
        "rpc.methods" -> listMethods()
        "app.info" -> appInfo()
        "debug.crash_report" -> crashReport()
        "debug.clear_crash" -> {
            context?.filesDir?.let { File(it, CRASH_FILE).delete() }
            JSONObject().put("cleared", true)
        }
        "debug.logcat" -> readLogcat(params)
        "ui.tree" -> onMain {
            val act = requireActivity()
            dumpView(act.window.decorView, act)
        }
        "ui.tap" -> onMain { tapView(requireActivity(), params) }
        "ui.input" -> onMain { inputText(requireActivity(), params) }
        "ui.scroll" -> onMain { scrollView(requireActivity(), params) }
        "ui.back" -> onMain {
            requireActivity().onBackPressed()
            JSONObject().put("ok", true)
        }
        "ui.shot" -> captureScreen(replyFd, outBundle)
        "ui.record_start" -> startRecording(params)
        "ui.record_stop" -> stopRecording(replyFd, outBundle)
        "ui.record" -> recordClip(params, replyFd, outBundle)
        else -> {
            val custom = handlers[method]
                ?: throw IllegalArgumentException("Unknown RPC method: " + method)
            custom.second.invoke(params, payloadFd)
        }
    }

    private fun listMethods(): JSONArray {
        val builtIn = listOf(
            "rpc.methods" to "List built-in and custom JSON-RPC debug methods",
            "app.info" to "Process PID, version, memory and resumed activity",
            "debug.crash_report" to "Last uncaught exception stacktrace and historical exit reasons",
            "debug.clear_crash" to "Delete stored last-crash file",
            "debug.logcat" to "Own-process logcat lines ({lines, filter})",
            "ui.tree" to "Active window View hierarchy with bounds, ids and text",
            "ui.tap" to "Tap by {id}, {text}, {desc} or screen {x, y}",
            "ui.input" to "Enter {text} into EditText matching {id} or {hint} (or focused field)",
            "ui.scroll" to "Scroll by {dx, dy} on {id} or first scrollable container",
            "ui.back" to "Press Back in the resumed activity",
            "ui.shot" to "Capture window PNG via PixelCopy streamed over reply_fd",
            "ui.record_start" to "Start background MP4 window recording ({fps, max_ms})",
            "ui.record_stop" to "Stop active MP4 window recording and stream video over reply_fd",
            "ui.record" to "Record window MP4 for {duration_ms, fps} and stream over reply_fd",
        )
        val arr = JSONArray()
        for ((name, desc) in builtIn) {
            arr.put(JSONObject().put("method", name).put("description", desc))
        }
        for ((name, pair) in handlers.entries.sortedBy { it.key }) {
            arr.put(JSONObject().put("method", name).put("description", pair.first))
        }
        return arr
    }

    private fun appInfo(): JSONObject {
        val ctx = context!!
        val pkgInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val rt = Runtime.getRuntime()
        val act = currentActivity.get()
        return JSONObject()
            .put("package", ctx.packageName)
            .put("version_name", pkgInfo.versionName ?: "")
            .put("pid", Process.myPid())
            .put("resumed_activity", act?.javaClass?.name ?: JSONObject.NULL)
            .put("used_memory_mb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024))
    }

    private fun crashReport(): JSONObject {
        val ctx = context!!
        val out = JSONObject()
        val file = File(ctx.filesDir, CRASH_FILE)
        if (file.isFile) {
            runCatching { JSONObject(file.readText()) }.getOrNull()?.let { out.put("last_crash", it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = JSONArray()
            for (info in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)) {
                val item = JSONObject()
                    .put("timestamp", info.timestamp)
                    .put("reason", info.reason)
                    .put("status", info.status)
                    .put("description", info.description ?: "")
                runCatching {
                    info.traceInputStream?.bufferedReader()?.use { r ->
                        item.put("trace", r.readText().take(8000))
                    }
                }
                exits.put(item)
            }
            out.put("exit_reasons", exits)
        }
        return out
    }

    private fun readLogcat(params: JSONObject): String {
        val count = params.optInt("lines", 120).coerceIn(1, 2000)
        val filter = params.optString("filter", "").trim()
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", count.toString(), "--pid=" + Process.myPid()))
        val raw = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor(3, TimeUnit.SECONDS)
        if (filter.isEmpty()) return raw
        val regex = Regex(filter, RegexOption.IGNORE_CASE)
        return raw.lineSequence().filter { regex.containsMatchIn(it) }.joinToString("\n")
    }

    private fun requireActivity(): Activity =
        currentActivity.get() ?: throw IllegalStateException("No resumed Activity. Call launch first.")

    private fun dumpView(view: View, act: Activity): JSONObject {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val idName = if (view.id != View.NO_ID) {
            runCatching { act.resources.getResourceEntryName(view.id) }.getOrNull()
        } else null
        val node = JSONObject()
            .put("class", view.javaClass.simpleName)
            .put("bounds", JSONArray(listOf(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)))
            .put("visible", view.visibility == View.VISIBLE)
            .put("clickable", view.isClickable)
            .put("enabled", view.isEnabled)
        if (!idName.isNullOrEmpty()) node.put("id", idName)
        if (view is TextView) {
            val txt = view.text?.toString().orEmpty()
            if (txt.isNotEmpty()) node.put("text", txt)
            val hint = view.hint?.toString().orEmpty()
            if (hint.isNotEmpty()) node.put("hint", hint)
        }
        if (view is EditText) node.put("editable", true)
        if (view is Checkable) node.put("checked", view.isChecked)
        val desc = view.contentDescription?.toString().orEmpty()
        if (desc.isNotEmpty()) node.put("desc", desc)
        if (view is ViewGroup && view.visibility == View.VISIBLE) {
            val children = JSONArray()
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.visibility == View.VISIBLE) children.put(dumpView(child, act))
            }
            if (children.length() > 0) node.put("children", children)
        }
        return node
    }

    private fun findView(root: View, act: Activity, pred: (View, String?) -> Boolean): View? {
        if (root.visibility != View.VISIBLE) return null
        val idName = if (root.id != View.NO_ID) {
            runCatching { act.resources.getResourceEntryName(root.id) }.getOrNull()
        } else null
        if (pred(root, idName)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findView(root.getChildAt(i), act, pred)?.let { return it }
            }
        }
        return null
    }

    private fun tapView(act: Activity, params: JSONObject): JSONObject {
        if (params.has("x") && params.has("y")) {
            val x = params.getDouble("x").toFloat()
            val y = params.getDouble("y").toFloat()
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0)
            act.window.decorView.dispatchTouchEvent(down)
            act.window.decorView.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
            return JSONObject().put("tapped_xy", JSONArray(listOf(x, y)))
        }
        val wantId = params.optString("id").trim()
        val wantText = params.optString("text").trim()
        val wantDesc = params.optString("desc").trim()
        val target = findView(act.window.decorView, act) { v, idName ->
            (wantId.isNotEmpty() && idName == wantId) ||
                (wantText.isNotEmpty() && v is TextView && v.text?.toString()?.contains(wantText, true) == true) ||
                (wantDesc.isNotEmpty() && v.contentDescription?.toString()?.contains(wantDesc, true) == true)
        } ?: throw IllegalArgumentException("No visible view matched tap selector.")
        var clickable: View? = target
        while (clickable != null && !clickable.isClickable && clickable.parent is View) {
            clickable = clickable.parent as View
        }
        val chosen = if (clickable != null && clickable.isClickable) clickable else target
        chosen.performClick()
        return JSONObject().put("tapped", chosen.javaClass.simpleName)
    }

    private fun inputText(act: Activity, params: JSONObject): JSONObject {
        val text = params.optString("text", "")
        val wantId = params.optString("id").trim()
        val wantHint = params.optString("hint").trim()
        val field = if (wantId.isEmpty() && wantHint.isEmpty()) {
            (act.currentFocus as? EditText)
                ?: (findView(act.window.decorView, act) { v, _ -> v is EditText } as? EditText)
        } else {
            findView(act.window.decorView, act) { v, idName ->
                v is EditText && (
                    (wantId.isNotEmpty() && idName == wantId) ||
                        (wantHint.isNotEmpty() && v.hint?.toString()?.contains(wantHint, true) == true)
                    )
            } as? EditText
        } ?: throw IllegalArgumentException("No matching EditText found.")
        field.requestFocus()
        field.setText(text)
        field.setSelection(text.length)
        return JSONObject().put("entered_chars", text.length)
    }

    private fun scrollView(act: Activity, params: JSONObject): JSONObject {
        val dx = params.optInt("dx", 0)
        val dy = params.optInt("dy", 600)
        val wantId = params.optString("id").trim()
        val target = findView(act.window.decorView, act) { v, idName ->
            if (wantId.isNotEmpty()) idName == wantId else (v.canScrollVertically(1) || v.canScrollVertically(-1) || v.canScrollHorizontally(1))
        } ?: act.window.decorView
        target.scrollBy(dx, dy)
        return JSONObject().put("scrolled", target.javaClass.simpleName)
    }

    private fun captureScreen(replyFd: ParcelFileDescriptor?, outBundle: Bundle): JSONObject {
        val fd = replyFd ?: throw IllegalArgumentException("Missing reply_fd for screenshot stream.")
        val (act, width, height) = onMain {
            val a = requireActivity()
            val d = a.window.decorView
            Triple(a, d.width.coerceAtLeast(1), d.height.coerceAtLeast(1))
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        val copyResult = AtomicReference(-1)
        runCatching {
            PixelCopy.request(act.window, bitmap, { code ->
                copyResult.set(code)
                latch.countDown()
            }, mainHandler)
        }.onFailure { latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        if (copyResult.get() != PixelCopy.SUCCESS) {
            onMain { act.window.decorView.draw(Canvas(bitmap)) }
        }
        var written = 0
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            val buf = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, buf)
            val bytes = buf.toByteArray()
            written = bytes.size
            out.write(bytes)
            out.flush()
        }
        bitmap.recycle()
        outBundle.putString("payload_mime", "image/png")
        outBundle.putString("payload_filename", "shot.png")
        return JSONObject().put("width", width).put("height", height).put("bytes", written)
    }

    private fun startRecording(params: JSONObject): JSONObject {
        synchronized(recordLock) {
            if (activeRecorder != null) {
                throw IllegalStateException("A window recording is already running; call ui.record_stop first.")
            }
            val fps = params.optInt("fps", 10).coerceIn(2, 24)
            val maxMs = params.optInt("max_ms", 15_000).coerceIn(500, 15_000)
            val session = WindowRecorder(fps, maxMs)
            activeRecorder = session
            session.start()
            return JSONObject().put("recording", true).put("fps", fps).put("max_ms", maxMs)
        }
    }

    private fun stopRecording(replyFd: ParcelFileDescriptor?, outBundle: Bundle): JSONObject {
        val fd = replyFd ?: throw IllegalArgumentException("Missing reply_fd for video stream.")
        val session = synchronized(recordLock) {
            val s = activeRecorder ?: throw IllegalStateException("No active recording; call ui.record_start first.")
            activeRecorder = null
            s
        }
        val bytes = session.stopAndEncode()
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            out.write(bytes)
            out.flush()
        }
        outBundle.putString("payload_mime", "video/mp4")
        outBundle.putString("payload_filename", "recording.mp4")
        return JSONObject()
            .put("frames", session.frameCount)
            .put("bytes", bytes.size)
    }

    private fun recordClip(params: JSONObject, replyFd: ParcelFileDescriptor?, outBundle: Bundle): JSONObject {
        val durationMs = params.optInt("duration_ms", 3_000).coerceIn(500, 15_000)
        val fps = params.optInt("fps", 10).coerceIn(2, 24)
        startRecording(JSONObject().put("fps", fps).put("max_ms", durationMs))
        Thread.sleep(durationMs.toLong())
        return stopRecording(replyFd, outBundle)
    }

    private inner class WindowRecorder(private val fps: Int, private val maxMs: Int) {
        @Volatile
        private var running = true
        private val captured = ArrayList<Pair<Bitmap, Long>>()
        private var worker: Thread? = null
        var frameCount: Int = 0
            private set

        fun start() {
            val t = Thread {
                val intervalMs = (1000L / fps.toLong()).coerceAtLeast(40L)
                val startNs = System.nanoTime()
                while (running) {
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    if (elapsedMs >= maxMs) break
                    val frame = grabWindowFrame()
                    if (frame != null) {
                        val ptsUs = ((System.nanoTime() - startNs) / 1_000L).coerceAtLeast(0L)
                        synchronized(captured) { captured.add(frame to ptsUs) }
                    }
                    val spent = ((System.nanoTime() - startNs) / 1_000_000L) - elapsedMs
                    val wait = intervalMs - spent
                    if (wait > 0L) runCatching { Thread.sleep(wait) }
                }
            }
            worker = t
            t.start()
        }

        fun stopAndEncode(): ByteArray {
            running = false
            runCatching { worker?.join(5_000L) }
            val frames = synchronized(captured) { ArrayList(captured) }
            if (frames.isEmpty()) throw IllegalStateException("No window frames were captured.")
            frameCount = frames.size
            val first = frames.first().first
            val evenW = ((first.width / 2) * 2).coerceAtLeast(64)
            val evenH = ((first.height / 2) * 2).coerceAtLeast(64)
            val out = File.createTempFile("pechka-win-", ".mp4", context?.cacheDir)
            var codec: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var started = false
            var surface: android.view.Surface? = null
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, evenW, evenH).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, (evenW * evenH * fps / 3).coerceIn(400_000, 4_000_000))
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec = enc
                enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surf = enc.createInputSurface()
                surface = surf
                enc.start()
                val mx = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = mx
                val info = MediaCodec.BufferInfo()
                var track = -1
                val dst = Rect(0, 0, evenW, evenH)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)

                fun drain(eos: Boolean) {
                    if (eos) enc.signalEndOfInputStream()
                    var spins = 0
                    while (spins < 400) {
                        val st = enc.dequeueOutputBuffer(info, 10_000L)
                        if (st == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            if (!eos) break
                            spins++
                        } else if (st == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (!started) {
                                track = mx.addTrack(enc.outputFormat)
                                mx.start()
                                started = true
                            }
                        } else if (st >= 0) {
                            val buf = enc.getOutputBuffer(st)
                            if (buf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && started) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                mx.writeSampleData(track, buf, info)
                            }
                            enc.releaseOutputBuffer(st, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                }

                for ((bmp, _) in frames) {
                    val c = surf.lockCanvas(null)
                    try {
                        c.drawColor(Color.BLACK)
                        c.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), dst, paint)
                    } finally {
                        surf.unlockCanvasAndPost(c)
                    }
                    drain(false)
                }
                drain(true)
                if (started) {
                    runCatching { mx.stop() }
                    started = false
                }
                return out.readBytes()
            } finally {
                for ((bmp, _) in frames) runCatching { bmp.recycle() }
                runCatching { surface?.release() }
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                if (started) runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
                runCatching { out.delete() }
            }
        }

        private fun grabWindowFrame(): Bitmap? = runCatching {
            val (act, rawW, rawH) = onMain {
                val a = requireActivity()
                val d = a.window.decorView
                Triple(a, d.width.coerceAtLeast(64), d.height.coerceAtLeast(64))
            }
            val scale = if (maxOf(rawW, rawH) > 960) 960.0 / maxOf(rawW, rawH).toDouble() else 1.0
            val w = (((rawW * scale).toInt() / 2) * 2).coerceAtLeast(64)
            val h = (((rawH * scale).toInt() / 2) * 2).coerceAtLeast(64)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val latch = CountDownLatch(1)
            val codeRef = AtomicReference(-1)
            runCatching {
                PixelCopy.request(act.window, bmp, { c ->
                    codeRef.set(c)
                    latch.countDown()
                }, mainHandler)
            }.onFailure { latch.countDown() }
            latch.await(1, TimeUnit.SECONDS)
            if (codeRef.get() != PixelCopy.SUCCESS) {
                onMain {
                    val canvas = Canvas(bmp)
                    canvas.scale(w.toFloat() / rawW.toFloat(), h.toFloat() / rawH.toFloat())
                    act.window.decorView.draw(canvas)
                }
            }
            bmp
        }.getOrNull()
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val res = AtomicReference<T>()
        val err = AtomicReference<Throwable>()
        mainHandler.post {
            try { res.set(block()) } catch (t: Throwable) { err.set(t) } finally { latch.countDown() }
        }
        if (!latch.await(8, TimeUnit.SECONDS)) throw IllegalStateException("Main thread timed out.")
        err.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return res.get() as T
    }

    private fun errorReply(id: Any?, code: Int, message: String, data: String? = null): Bundle {
        val err = JSONObject().put("code", code).put("message", message)
        if (!data.isNullOrEmpty()) err.put("data", data)
        val env = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("error", err)
        return Bundle().apply { putString("json", env.toString()) }
    }

    /** Custom methods: the app can be inspected and driven, not just tapped. */
    private fun registerFactory() {
        register("factory.status", "Factory app state: counter, label, last action, version") { _, _ ->
            FactoryState.status(context!!)
        }
        register("factory.bump", "Increment the tap counter") { _, _ ->
            FactoryState.bump(context!!)
        }
        register("factory.reset", "Reset the counter to zero") { _, _ ->
            FactoryState.reset(context!!)
        }
        register("factory.set_label", "Set the label text: {text}") { params, _ ->
            FactoryState.setLabel(context!!, params.optString("text", ""))
        }
        register("factory.reload", "Reload the bundled page in the WebView") { _, _ ->
            FactoryState.reload(context!!)
        }
        register("factory.toast", "Show a toast: {text}") { params, _ ->
            FactoryState.toast(context!!, params.optString("text", ""))
        }
    }

    companion object {
        private const val ALLOWED_CALLER = "ai.pechka"
        private const val CRASH_FILE = "pechka_last_crash.json"
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var currentActivity = WeakReference<Activity>(null)

        private val recordLock = Any()

        @Volatile
        private var activeRecorder: WindowRecorder? = null

        @Volatile
        private var hookInstalled = false

        private val handlers = ConcurrentHashMap<String, Pair<String, (JSONObject, ParcelFileDescriptor?) -> Any>>()

        fun register(
            method: String,
            description: String,
            handler: (params: JSONObject, payloadFd: ParcelFileDescriptor?) -> Any,
        ) {
            handlers[method.trim()] = description to handler
        }

        private fun installCrashHook(context: Context) {
            if (hookInstalled) return
            hookInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    val report = JSONObject()
                        .put("time", System.currentTimeMillis())
                        .put("thread", thread.name)
                        .put("exception", throwable.javaClass.name)
                        .put("message", throwable.message ?: "")
                        .put("stacktrace", sw.toString().take(16000))
                    File(context.filesDir, CRASH_FILE).writeText(report.toString(2))
                }
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
```