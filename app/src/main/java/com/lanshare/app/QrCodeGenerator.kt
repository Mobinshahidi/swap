package com.lanshare.app

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeGenerator {
    // Default QR colors follow the warm app palette instead of stark black/white.
    private const val DEFAULT_INK = 0xFF1E1E1D.toInt()
    private const val DEFAULT_PAPER = 0xFFF5F2EC.toInt()

    fun render(
        content: String,
        targetSizePx: Int,
        ink: Int = DEFAULT_INK,
        paper: Int = DEFAULT_PAPER
    ): Bitmap {
        // Scanners need dark modules on a light background; if a custom theme
        // flips or flattens the contrast, fall back to the default pair.
        val safeInk = if (luminance(ink) < 0.45f) ink else DEFAULT_INK
        val safePaper = if (luminance(paper) > 0.55f) paper else DEFAULT_PAPER

        val hints = hashMapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, targetSizePx, targetSizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix[x, y]) safeInk else safePaper
            }
        }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun luminance(color: Int): Float {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f
    }
}
