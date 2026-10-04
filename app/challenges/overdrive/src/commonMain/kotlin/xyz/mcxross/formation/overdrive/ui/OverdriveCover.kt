package xyz.mcxross.formation.overdrive

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import xyz.mcxross.formation.overdrive.resources.Res
import xyz.mcxross.formation.overdrive.resources.overdrive_cover

@Composable
internal fun OverdriveCover() {
  Image(painterResource(Res.drawable.overdrive_cover), null, Modifier.fillMaxSize())
}
