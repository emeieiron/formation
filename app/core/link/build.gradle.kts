plugins { id("formation.multiplatform") }

kotlin {
  sourceSets {
    commonMain.dependencies {
      // linkHttpClient() hands out a Ktor client, so Ktor is part of this module's API.
      api(libs.ktor.client.core)
      implementation(libs.ktor.client.websockets)
    }
    androidMain.dependencies {
      implementation(libs.ktor.client.okhttp)
      implementation(libs.ktor.server.core)
      implementation(libs.ktor.server.cio)
      implementation(libs.ktor.server.websockets)
    }
    iosMain.dependencies { implementation(libs.ktor.client.darwin) }
  }
}
