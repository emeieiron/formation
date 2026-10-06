package xyz.mcxross.formation.ui.screens

import androidx.compose.ui.graphics.ImageBitmap

// Skia builds mipmaps on demand for FilterQuality.Medium.
internal actual fun ImageBitmap.withMipmaps(): ImageBitmap = this
