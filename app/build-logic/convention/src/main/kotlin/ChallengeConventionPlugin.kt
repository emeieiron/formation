import org.gradle.api.Plugin
import org.gradle.api.Project

class ChallengeConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) =
    with(target) {
      pluginManager.apply("formation.compose")
      pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

      kotlinMultiplatform.sourceSets.getByName("commonMain").dependencies {
        api(project(":challenges:api"))
        implementation(libs.library("kotlinx-serialization-json"))
      }
    }
}
