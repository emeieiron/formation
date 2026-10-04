import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.composeCompiler)
}

android {
  namespace = "xyz.mcxross.formation"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "xyz.mcxross.formation"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = 1
    versionName = "0.1.0"
    buildConfigField(
      "String",
      "SOLANA_RPC_URL",
      "\"${project.findProperty("formation.rpcUrl") ?: "https://api.testnet.solana.com"}\"",
    )
    buildConfigField(
      "String",
      "SOLANA_CLUSTER",
      "\"${project.findProperty("formation.cluster") ?: "testnet"}\"",
    )
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "/META-INF/INDEX.LIST"
      excludes += "/META-INF/io.netty.versions.properties"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }

dependencies {
  implementation(projects.shared)
  implementation(projects.core.link)
  implementation(projects.core.sensors)
  implementation(projects.core.crypto)

  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.ktx)
  // Play services still pulls Fragment 1.0, which predates activity results.
  implementation(libs.androidx.fragment)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.mwa.clientlib)
  implementation(libs.play.codeScanner)

  debugImplementation(libs.compose.uiTooling)
}
