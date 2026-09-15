package com.pdyy.pdhbar.scanner

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

class MLKitBarcodeAnalyzer(
    private val onFailure: (Exception) -> Unit = { Log.e("MLKitBarcodeAnalyzer", "Barcode analysis failed", it) },
    private val onScanned: (String) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val scanner = BarcodeScanning.getClient()
    private val scannerLock = Any()
    private val closed = AtomicBoolean(false)

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val frame = ScannerFrame(imageProxy::close, onFailure)
        frame.protect {
            // Serialize process() with close(), including fromMediaImage failures.
            synchronized(scannerLock) {
                if (closed.get()) {
                    frame.close()
                    return@protect
                }
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    frame.close()
                    return@protect
                }

                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image).addOnCompleteListener { task ->
                    try {
                        frame.protect {
                            // A disposed preview must not publish a late decoded result.
                            if (!closed.get()) {
                                if (task.isSuccessful) {
                                    task.result.firstOrNull()?.rawValue
                                        ?.takeIf(String::isNotBlank)?.let(onScanned)
                                } else if (!task.isCanceled) {
                                    task.exception?.let { throw it }
                                }
                            }
                        }
                    } finally {
                        frame.close()
                    }
                }
            }
        }
    }

    override fun close() {
        synchronized(scannerLock) {
            if (closed.compareAndSet(false, true)) scanner.close()
        }
    }
}
