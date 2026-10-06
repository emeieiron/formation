package xyz.mcxross.formation.ui.screens

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

internal actual fun ImageBitmap.withMipmaps(): ImageBitmap = apply { asAndroidBitmap().setHasMipMap(true) }
