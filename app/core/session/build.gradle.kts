plugins {
  id("formation.multiplatform")
  alias(libs.plugins.kotlinSerialization)
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.core.model)
      api(projects.core.link)
      api(projects.core.crypto)
      implementation(libs.kotlinx.serialization.json)
    }
  }
}
