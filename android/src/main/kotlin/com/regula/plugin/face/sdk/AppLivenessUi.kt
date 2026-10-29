package com.regula.plugin.face.sdk

import android.app.Activity
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.regula.facesdk.fragment.BaseFaceLivenessProcessingFragment
import com.regula.facesdk.fragment.FaceUiFragment
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterTextureView
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import kotlin.math.roundToInt

internal object AppLivenessSession {
    private var data = emptyMap<String, String>()
    fun set(input: JSONObject?) {
        data = mapOf(
            "employeeImage" to (input?.optString("employeeImage", "") ?: ""),
            "employeeLabel" to (input?.optString("employeeLabel", "") ?: ""),
        )
    }
    fun snapshot(): Map<String, String> = data.toMap()
    fun clear() { data = emptyMap() }
}

/** Renders the host application's real Flutter widgets above the SDK camera.
 * Plugin auto-registration is disabled: a second Face SDK/camera plugin must
 * never replace the primary engine's native callbacks or camera ownership.
 */
private class AppFlutterUi(
    private val root: View,
    private val activity: Activity,
    private val state: () -> Map<String, Any>,
    private val close: () -> Unit,
    private val retry: (() -> Unit)? = null,
) : ViewTreeObserver.OnGlobalLayoutListener {
    private val engine = FlutterEngine(root.context, null, false)
    private val channel = MethodChannel(engine.dartExecutor.binaryMessenger, "myemployee/regula_ui")
    private val flutterView = FlutterView(root.context, FlutterTextureView(root.context).apply { isOpaque = false })
    private var ready = false
    private var destroyed = false
    private var lastState: Map<String, Any>? = null
    private val layer = FrameLayout(activity)
    private var viewport: Map<String, Double>? = null

    init {
        restoreSystemBars()
        flutterView.setBackgroundColor(Color.TRANSPARENT)
        // Attach above the SDK root, not inside its inset/padded UI slot.
        // This avoids double status-bar padding in the Flutter app bar.
        activity.findViewById<ViewGroup>(android.R.id.content).addView(
            layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        layer.addView(
            flutterView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "ready" -> {
                    ready = true
                    result.success((state() + systemInsets()).also { lastState = it } + AppLivenessSession.snapshot())
                }
                "cameraViewport" -> {
                    val args = call.arguments as? Map<*, *>
                    val fields = listOf("left", "top", "width", "height", "pixelRatio")
                    val values = fields.associateWith { (args?.get(it) as? Number)?.toDouble() ?: Double.NaN }
                    if (state()["phase"] != "camera" || values.values.any { !it.isFinite() } ||
                        values.getValue("width") <= 0 || values.getValue("height") <= 0 ||
                        values.getValue("pixelRatio") <= 0 || sdkView("cameraConstraintLayout") == null) {
                        result.error("VIEWPORT_UNAVAILABLE", "Camera presentation is not ready", null)
                    } else {
                        viewport = values
                        applyViewport()
                        result.success(null)
                    }
                }
                "close" -> {
                    result.success(null)
                    root.post { if (!destroyed) close() }
                }
                "retry" -> {
                    if (state()["phase"] != "retry" || retry == null) {
                        result.error("NOT_READY", "Retry is not available in this SDK state", null)
                    } else {
                        result.success(null)
                        root.post { if (!destroyed) retry.invoke() }
                    }
                }
                else -> result.notImplemented()
            }
        }
        flutterView.attachToFlutterEngine(engine)
        val bundle = FlutterInjector.instance().flutterLoader().findAppBundlePath()
        engine.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint(bundle, "regulaLivenessUi"))
        root.viewTreeObserver.addOnGlobalLayoutListener(this)
        resume()
    }

    fun refresh() {
        if (!ready || destroyed) return
        applyViewport()
        val next = state() + systemInsets()
        if (next != lastState) {
            lastState = next
            channel.invokeMethod("state", next)
        }
    }

    override fun onGlobalLayout() = refresh()
    fun resume() { if (!destroyed) { restoreSystemBars(); engine.lifecycleChannel.appIsResumed() } }
    fun pause() { if (!destroyed) engine.lifecycleChannel.appIsInactive() }

    fun dispose() {
        if (destroyed) return
        destroyed = true
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(this)
        channel.setMethodCallHandler(null)
        flutterView.detachFromFlutterEngine()
        (layer.parent as? ViewGroup)?.removeView(layer)
        engine.lifecycleChannel.appIsDetached()
        engine.destroy()
    }

    private fun sdkView(name: String): View? {
        // Verified against the pinned Face SDK 8.4.5465 layout resources.
        val id = activity.resources.getIdentifier(name, "id", activity.packageName)
        return if (id == 0) null else activity.findViewById(id)
    }

    @Suppress("DEPRECATION")
    private fun restoreSystemBars() {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun systemInsets(): Map<String, Double> {
        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
        val density = activity.resources.displayMetrics.density.toDouble()
        return mapOf(
            "topInset" to ((insets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())?.top ?: 0) / density),
            "bottomInset" to ((insets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0) / density),
        )
    }

    private fun applyViewport() {
        val rect = viewport ?: return
        val camera = sdkView("cameraConstraintLayout") as? ViewGroup ?: return
        val params = camera.layoutParams as? ConstraintLayout.LayoutParams ?: return
        val parent = camera.parent as? View ?: return
        val viewOrigin = IntArray(2).also { flutterView.getLocationInWindow(it) }
        val parentOrigin = IntArray(2).also { parent.getLocationInWindow(it) }
        val ratio = rect.getValue("pixelRatio")
        val width = (rect.getValue("width") * ratio).roundToInt().coerceIn(1, parent.width.coerceAtLeast(1))
        val height = (rect.getValue("height") * ratio).roundToInt().coerceIn(1, parent.height.coerceAtLeast(1))
        val left = (rect.getValue("left") * ratio + viewOrigin[0] - parentOrigin[0]).roundToInt()
            .coerceIn(0, (parent.width - width).coerceAtLeast(0))
        val top = (rect.getValue("top") * ratio + viewOrigin[1] - parentOrigin[1]).roundToInt()
            .coerceIn(0, (parent.height - height).coerceAtLeast(0))
        if (params.width != width || params.height != height || params.leftMargin != left || params.topMargin != top) {
            params.width = width; params.height = height
            params.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.rightToRight = ConstraintLayout.LayoutParams.UNSET
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            params.startToStart = ConstraintLayout.LayoutParams.UNSET
            params.endToEnd = ConstraintLayout.LayoutParams.UNSET
            params.leftMargin = left; params.topMargin = top
            camera.layoutParams = params
        }
        camera.clipChildren = true
        camera.clipToPadding = true
        // Hide visual guidance only. Keep its layout and SDK processing alive.
        sdkView("faceOverlayLayout")?.alpha = 0f
        sdkView("animationSurface")?.alpha = 0f
    }
}

class AppLivenessCameraFragment : FaceUiFragment() {
    private var appUi: AppFlutterUi? = null
    override fun getResourceLayoutId() = R.layout.app_liveness_camera
    override fun getCloseButton(view: View): View = view.findViewById(R.id.app_face_close)
    override fun getSwapCameraButton(view: View): View = view.findViewById(R.id.app_face_swap)
    override fun getFlashLightButton(view: View): View = view.findViewById(R.id.app_face_flash)
    override fun getNotificationView(view: View): TextView = view.findViewById(R.id.app_face_notification)
    override fun initUI(view: View) {
        super.initUI(view)
        appUi = AppFlutterUi(view, requireActivity(),
            state = { mapOf("phase" to "camera", "message" to getNotificationView(view).text.toString()) },
            close = { getCloseButton(view).performClick() },
        )
        getNotificationView(view).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { appUi?.refresh() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    override fun onResume() { super.onResume(); appUi?.resume() }
    override fun onPause() { appUi?.pause(); super.onPause() }
    override fun onDestroyView() { appUi?.dispose(); appUi = null; super.onDestroyView() }
}

class AppLivenessProcessingFragment : BaseFaceLivenessProcessingFragment() {
    private var appUi: AppFlutterUi? = null
    private var errors = emptyList<Int>()
    override fun getResourceLayoutId() = R.layout.app_liveness_processing
    override fun getCloseButton(view: View): View = view.findViewById(R.id.app_face_close)
    override fun getCancelButton(view: View): View = getCloseButton(view)
    override fun getRetryButton(view: View): View = view.findViewById(R.id.app_face_retry_button)
    override fun getProcessingLayout(view: View): View = view.findViewById(R.id.app_face_processing)
    override fun getRetryLayout(view: View): View = view.findViewById(R.id.app_face_retry)
    override fun getVerifyLayout(view: View): View = view.findViewById(R.id.app_face_verified)
    override fun getDefaultRetryView(view: View): View = view.findViewById(R.id.app_face_retry_default)
    override fun getBadQualityRetryView(view: View): View = view.findViewById(R.id.app_face_retry_quality)
    override fun initUI(view: View) {
        super.initUI(view)
        appUi = AppFlutterUi(view, requireActivity(),
            state = {
                val phase = when {
                    getRetryLayout(view).isShown -> "retry"
                    getVerifyLayout(view).isShown -> "verified"
                    else -> "processing"
                }
                mapOf("phase" to phase, "codes" to errors,
                    "quality" to getBadQualityRetryView(view).isShown)
            },
            close = { getCancelButton(view).performClick() },
            retry = { getRetryButton(view).performClick() },
        )
    }
    override fun faceSdkError(errors: IntArray) { this.errors = errors.toList(); appUi?.refresh() }
    override fun onResume() { super.onResume(); appUi?.resume() }
    override fun onPause() { appUi?.pause(); super.onPause() }
    override fun onDestroyView() { appUi?.dispose(); appUi = null; super.onDestroyView() }
}
