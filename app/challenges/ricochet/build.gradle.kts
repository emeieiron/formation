plugins { id("formation.challenge") }

compose.resources { packageOfResClass = "xyz.mcxross.formation.ricochet.resources" }

kotlin {
  android { androidResources { enable = true } }
  sourceSets {
    commonMain.dependencies { implementation(libs.compose.components.resources) }
  }
}
