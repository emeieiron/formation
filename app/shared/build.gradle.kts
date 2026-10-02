plugins {
  id("formation.compose")
  alias(libs.plugins.kotlinSerialization)
}

kotlin {
  android { androidResources { enable = true } }

  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "Shared"
      isStatic = true
    }
  }

  sourceSets {
    commonMain.dependencies {
      api(projects.design)
      api(projects.core.model)
      api(projects.core.session)
      api(projects.core.sensors)
      implementation(projects.core.crypto)
      implementation(projects.core.link)

      api(projects.challenges.api)

      implementation(projects.solana.vault)

      implementation(libs.compose.components.resources)
      implementation(libs.navigationevent.compose)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.ktor.client.core)
    }
  }
}

compose.resources { packageOfResClass = "xyz.mcxross.formation.resources" }
