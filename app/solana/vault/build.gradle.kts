plugins { id("formation.multiplatform") }

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(libs.web3.solana)
      api(libs.ktor.client.core)
      implementation(projects.core.crypto)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies { implementation(libs.ktor.client.mock) }
  }
}

tasks.withType<Test>().configureEach {
  systemProperty(
    "formation.idl",
    rootProject.file("../program/formation-vault/idl/formation_vault.json").absolutePath,
  )
}
