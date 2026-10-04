package xyz.mcxross.formation.ricochet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import xyz.mcxross.formation.ricochet.resources.Res
import xyz.mcxross.formation.ricochet.resources.ricochet_cover

@Composable
internal fun RicochetCover() {
  Image(painterResource(Res.drawable.ricochet_cover), null, Modifier.fillMaxSize())
}
