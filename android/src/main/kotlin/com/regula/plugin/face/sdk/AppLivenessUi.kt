package com.regula.plugin.face.sdk

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import com.regula.facesdk.fragment.BaseFaceLivenessProcessingFragment
import com.regula.facesdk.fragment.FaceUiFragment
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterTextureView
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

/** Renders the host application's real Flutter widgets above the SDK camera.
 * Plugin auto-registration is disabled: a second Face SDK/camera plugin must
 * never replace the primary engine's native callbacks or camera ownership.
 */
private class AppFlutterUi(
    private val root: View,
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

    init {
        flutterView.setBackgroundColor(Color.TRANSPARENT)
        root.findViewById<FrameLayout>(R.id.app_face_flutter).addView(
            flutterView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "ready" -> {
                    ready = true
                    result.success(state().also { lastState = it })
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
        val next = state()
        if (next != lastState) {
            lastState = next
            channel.invokeMethod("state", next)
        }
    }

    override fun onGlobalLayout() = refresh()
    fun resume() { if (!destroyed) engine.lifecycleChannel.appIsResumed() }
    fun pause() { if (!destroyed) engine.lifecycleChannel.appIsInactive() }

    fun dispose() {
        if (destroyed) return
        destroyed = true
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(this)
        channel.setMethodCallHandler(null)
        flutterView.detachFromFlutterEngine()
        engine.lifecycleChannel.appIsDetached()
        engine.destroy()
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
        appUi = AppFlutterUi(view,
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
        appUi = AppFlutterUi(view,
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
