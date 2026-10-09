plugins { id("formation.multiplatform") }

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.solana.vault)
      implementation(projects.core.crypto)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(projects.core.crypto)
      implementation(libs.ktor.client.mock)
      implementation(libs.kotlinx.serialization.json)
    }
    androidHostTest.dependencies { implementation(libs.ktor.client.okhttp) }
  }
}

tasks.withType<Test>().configureEach {
  systemProperty(
    "formation.ore.mainnet",
    providers.gradleProperty("oreMainnetTest").getOrElse("false"),
  )
  systemProperty(
    "formation.ore.rpcUrl",
    providers
      .gradleProperty("formation.oreRpcUrl")
      .getOrElse("https://api.mainnet-beta.solana.com"),
  )
  systemProperty(
    "formation.ore.devnet",
    providers.gradleProperty("oreDevnetTest").getOrElse("false"),
  )
  systemProperty(
    "formation.ore.devnet.write",
    providers.gradleProperty("oreDevnetWriteTest").getOrElse("false"),
  )
  systemProperty("formation.repository", rootProject.projectDir.parentFile.absolutePath)
  systemProperty(
    "formation.ore.writeReport",
    layout.buildDirectory.file("reports/ore-devnet-writes.json").get().asFile.absolutePath,
  )
}
