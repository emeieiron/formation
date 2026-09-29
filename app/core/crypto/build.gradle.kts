plugins { id("formation.multiplatform") }

kotlin {
  sourceSets {
    commonMain.dependencies { implementation(libs.kotlincrypto.sha2) }
    getByName("androidHostTest").dependencies { implementation(libs.bouncycastle) }
  }
}
