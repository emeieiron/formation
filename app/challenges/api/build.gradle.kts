plugins {
  id("formation.compose")
  alias(libs.plugins.kotlinSerialization)
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.core.session)
      api(projects.core.sensors)
      api(projects.design)
      implementation(libs.kotlinx.serialization.json)
    }
  }
}
