rootProject.name = "Formation"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  includeBuild("build-logic")
  repositories {
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
  }
}

include(":design")

include(":core:model")

include(":core:crypto")

include(":core:link")

include(":core:sensors")

include(":core:session")

include(":challenges:api")

include(":challenges:overdrive")

include(":challenges:ricochet")

include(":challenges:mosaic")

include(":challenges:longshot")

include(":solana:vault")

include(":solana:ore")

include(":shared")

include(":androidApp")
