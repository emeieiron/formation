import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val rpcUrl = providers.gradleProperty("formation.rpcUrl").orElse("https://api.testnet.solana.com")
val cluster = providers.gradleProperty("formation.cluster").orElse("testnet")

// A distributable release is signed with the key CI passes in; without it, release builds stay
// unsigned for local use. See .github/workflows/release-android.yml.
val releaseKeystore = providers.gradleProperty("formationKeystoreFile")
val releaseSigned = releaseKeystore.isPresent

// Release versions come from the tag: v1.2.3 builds versionName 1.2.3 and versionCode 10203.
val formationVersionName = providers.gradleProperty("formationVersionName").orElse("0.1.0")
val formationVersionCode = providers.gradleProperty("formationVersionCode").map { it.toInt() }.orElse(1)

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
    versionCode = formationVersionCode.get()
    versionName = formationVersionName.get()
    buildConfigField("String", "SOLANA_RPC_URL", "\"${rpcUrl.get()}\"")
    buildConfigField("String", "SOLANA_CLUSTER", "\"${cluster.get()}\"")
  }

  // Two paths through the app. dev plays without a Seeker: a phone can pretend to be one, rewards can be
  // simulated and emulators get simulated motion. prod is what people install: only a Seeker hosts.
  flavorDimensions += "path"
  productFlavors {
    create("dev") {
      dimension = "path"
      applicationIdSuffix = ".dev"
      versionNameSuffix = "-dev"
      buildConfigField("boolean", "DEVELOPER", "true")
    }
    create("prod") {
      dimension = "path"
      buildConfigField("boolean", "DEVELOPER", "false")
    }
  }

  signingConfigs {
    if (releaseSigned) {
      create("release") {
        storeFile = file(releaseKeystore.get())
        storePassword = providers.gradleProperty("formationKeystorePassword").get()
        keyAlias = providers.gradleProperty("formationKeyAlias").get()
        keyPassword = providers.gradleProperty("formationKeyPassword").get()
      }
    }
  }

  buildTypes {
    release {
      if (releaseSigned) {
        signingConfig = signingConfigs.getByName("release")
        // A signed build is one people install: it must reach a public cluster over HTTPS.
        check(rpcUrl.get().startsWith("https://")) { "A signed release needs an HTTPS formation.rpcUrl, not ${rpcUrl.get()}" }
        check(cluster.get() in setOf("devnet", "testnet", "mainnet-beta")) { "A signed release can't target ${cluster.get()}" }
      }
      // R8 shrinks, optimises and obfuscates the release; resources nothing references go too.
      isMinifyEnabled = true
      isShrinkResources = true
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
