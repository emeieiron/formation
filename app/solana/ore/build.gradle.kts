plugins { id("formation.multiplatform") }

kotlin {
  sourceSets {
    commonMain.dependencies { api(projects.solana.vault) }
    commonTest.dependencies {
      implementation(projects.core.crypto)
      implementation(libs.ktor.client.mock)
      implementation(libs.kotlinx.serialization.json)
    }
    androidHostTest.dependencies { implementation(libs.ktor.client.okhttp) }
  }
}

tasks.withType<Test>().configureEach {
  systemProperty("formation.ore.devnet", providers.gradleProperty("oreDevnetTest").getOrElse("false"))
  systemProperty("formation.ore.devnet.write", providers.gradleProperty("oreDevnetWriteTest").getOrElse("false"))
  systemProperty("formation.repository", rootProject.projectDir.parentFile.absolutePath)
  systemProperty("formation.ore.writeReport", layout.buildDirectory.file("reports/ore-devnet-writes.json").get().asFile.absolutePath)
}
