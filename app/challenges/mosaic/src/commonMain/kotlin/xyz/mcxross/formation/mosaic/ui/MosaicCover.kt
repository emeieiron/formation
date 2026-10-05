package xyz.mcxross.formation.mosaic.ui

import androidx.compose.runtime.Composable
import xyz.mcxross.formation.design.components.MotionCover
import xyz.mcxross.formation.mosaic.resources.Res
import xyz.mcxross.formation.mosaic.resources.mosaic_cover
import xyz.mcxross.formation.mosaic.resources.mosaic_motion

@Composable
internal fun MosaicCover() {
  MotionCover(Res.drawable.mosaic_cover, Res.drawable.mosaic_motion)
}
