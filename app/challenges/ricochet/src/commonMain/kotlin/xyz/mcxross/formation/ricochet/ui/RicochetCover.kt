package xyz.mcxross.formation.ricochet.ui

import androidx.compose.runtime.Composable
import xyz.mcxross.formation.design.components.MotionCover
import xyz.mcxross.formation.ricochet.resources.Res
import xyz.mcxross.formation.ricochet.resources.ricochet_cover
import xyz.mcxross.formation.ricochet.resources.ricochet_motion

@Composable
internal fun RicochetCover() {
  MotionCover(Res.drawable.ricochet_cover, Res.drawable.ricochet_motion)
}
