import org.gradle.api.Plugin
import org.gradle.api.Project

class ComposeConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) =
    with(target) {
      pluginManager.apply("formation.multiplatform")
      pluginManager.apply("org.jetbrains.compose")
      pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

      kotlinMultiplatform.sourceSets.getByName("commonMain").dependencies {
        implementation(libs.library("compose-runtime"))
        implementation(libs.library("compose-foundation"))
        implementation(libs.library("compose-ui"))
      }
    }
}
