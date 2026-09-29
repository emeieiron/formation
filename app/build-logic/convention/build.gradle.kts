plugins { `kotlin-dsl` }

dependencies {
  // compileOnly: the root project puts the real plugins on the classpath, once.
  compileOnly(libs.gradlePlugin.android)
  compileOnly(libs.gradlePlugin.kotlin)
  compileOnly(libs.gradlePlugin.compose)
  compileOnly(libs.gradlePlugin.composeCompiler)
}

gradlePlugin {
  plugins {
    register("multiplatform") {
      id = "formation.multiplatform"
      implementationClass = "MultiplatformConventionPlugin"
    }
    register("compose") {
      id = "formation.compose"
      implementationClass = "ComposeConventionPlugin"
    }
    register("challenge") {
      id = "formation.challenge"
      implementationClass = "ChallengeConventionPlugin"
    }
  }
}
