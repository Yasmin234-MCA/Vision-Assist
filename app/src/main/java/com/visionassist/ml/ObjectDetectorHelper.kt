package com.visionassist.ml

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

class ObjectDetectorHelper(
    private val context: Context,
    private val listener: DetectorListener?
) {

    // Default options provide 400+ specific labels
    private val labelerOptions = ImageLabelerOptions.Builder()
        .setConfidenceThreshold(0.4f)
        .build()

    private val imageLabeler = ImageLabeling.getClient(labelerOptions)

    init {
        Log.d("Detector", "✅ ML Kit ImageLabeler initialized (400+ labels)")
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun detect(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        imageLabeler.process(image)
            .addOnSuccessListener { labels ->
                listener?.onResults(labels)
            }
            .addOnFailureListener { e ->
                Log.e("Detector", "❌ Labeling failed", e)
                listener?.onError(e.message ?: "Unknown error")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    interface DetectorListener {
        fun onError(error: String)
        fun onResults(results: List<ImageLabel>)
    }
}
