package com.regula.plugin.face.sdk

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.regula.facesdk.fragment.BaseFaceLivenessProcessingFragment
import com.regula.facesdk.fragment.FaceUiFragment

// App-owned views are plugged into the supported SDK fragment hooks.
// The SDK retains camera/session ownership, liveness decisions and retry actions.
private fun styleAppText(view: View) {
    if (view is TextView) {
        val title = view.id == R.id.app_face_title
        val asset = if (title) "BricolageGrotesque-Medium.ttf" else "GTWalsheimPro-Medium.ttf"
        view.typeface = runCatching {
            Typeface.createFromAsset(view.context.assets, "flutter_assets/assets/fonts/$asset")
        }.getOrDefault(Typeface.create("sans-serif-medium", Typeface.NORMAL))
    }
    if (view is ViewGroup) for (i in 0 until view.childCount) styleAppText(view.getChildAt(i))
}

class AppLivenessCameraFragment : FaceUiFragment() {
    override fun getResourceLayoutId() = R.layout.app_liveness_camera
    override fun getCloseButton(view: View): View = view.findViewById(R.id.app_face_close)
    override fun getSwapCameraButton(view: View): View = view.findViewById(R.id.app_face_swap)
    override fun getFlashLightButton(view: View): View = view.findViewById(R.id.app_face_flash)
    override fun getNotificationView(view: View): TextView = view.findViewById(R.id.app_face_notification)
    override fun initUI(view: View) {
        super.initUI(view)
        styleAppText(view)
    }
}

class AppLivenessProcessingFragment : BaseFaceLivenessProcessingFragment() {
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
        styleAppText(view)
    }
    override fun faceSdkError(errors: IntArray) {
        val message = when {
            errors.any { it == 244 || it == 245 || it == 254 || it == 255 } ->
                "Liveness belum lolos. Gunakan wajah langsung, bukan foto atau video dari layar."
            errors.any { it == 231 || it == 232 || it == 240 } ->
                "Pastikan wajah tidak tertutup dan pencahayaan merata, lalu coba lagi."
            else -> "Pemeriksaan wajah belum berhasil. Atur posisi wajah dan coba lagi."
        }
        view?.findViewById<TextView>(R.id.app_face_retry_default)?.text = message
    }
}
