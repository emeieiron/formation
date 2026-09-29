plugins {
  id("formation.multiplatform")
  alias(libs.plugins.kotlinSerialization)
}

kotlin { sourceSets { commonMain.dependencies { api(libs.kotlinx.serialization.json) } } }
