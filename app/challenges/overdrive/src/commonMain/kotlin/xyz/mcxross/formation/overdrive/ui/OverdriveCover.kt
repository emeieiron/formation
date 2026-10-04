package xyz.mcxross.formation.overdrive

import androidx.compose.runtime.Composable
import xyz.mcxross.formation.design.components.MotionCover
import xyz.mcxross.formation.overdrive.resources.Res
import xyz.mcxross.formation.overdrive.resources.overdrive_cover
import xyz.mcxross.formation.overdrive.resources.overdrive_motion

@Composable
internal fun OverdriveCover() {
  MotionCover(Res.drawable.overdrive_cover, Res.drawable.overdrive_motion)
}
