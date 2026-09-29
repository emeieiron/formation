package xyz.mcxross.formation.design.components

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

actual fun encodeQr(text: String): QrMatrix? = runCatching {
  val matrix =
    Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
      .matrix
  val size = matrix.width
  QrMatrix(size, BooleanArray(size * size) { i -> matrix.get(i % size, i / size).toInt() == 1 })
}
  .getOrNull()
