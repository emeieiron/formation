plugins { id("formation.compose") }

kotlin {
  android { androidResources { enable = true } }

  sourceSets {
    commonMain.dependencies {
      api(libs.compose.runtime)
      api(libs.compose.foundation)
      api(libs.compose.ui)
      implementation(libs.compose.components.resources)
      implementation(libs.navigationevent.compose)
    }
    androidMain.dependencies { implementation(libs.zxing.core) }
  }
}

compose.resources { packageOfResClass = "xyz.mcxross.formation.design.resources" }
