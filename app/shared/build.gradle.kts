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
    commonTest.dependencies { implementation(libs.ktor.client.mock) }
    commonMain.dependencies {
      api(projects.design)
      api(projects.core.model)
      api(projects.core.session)
      api(projects.core.sensors)
      implementation(projects.core.crypto)
      implementation(projects.core.link)

      api(projects.challenges.api)
      implementation(projects.challenges.overdrive)
      implementation(projects.challenges.ricochet)
      implementation(projects.challenges.mosaic)

      implementation(projects.solana.vault)

      implementation(libs.compose.components.resources)
      implementation(libs.navigationevent.compose)
      implementation(libs.androidx.lifecycle.runtimeCompose)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.ktor.client.core)
    }
  }
}

compose.resources { packageOfResClass = "xyz.mcxross.formation.resources" }
