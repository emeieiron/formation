import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class MultiplatformConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) =
    with(target) {
      pluginManager.apply("org.jetbrains.kotlin.multiplatform")
      pluginManager.apply("com.android.kotlin.multiplatform.library")

      extensions.configure<KotlinMultiplatformExtension> {
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>(
          "android"
        ) {
          namespace = formationNamespace
          compileSdk = libs.int("android-compileSdk")
          minSdk = libs.int("android-minSdk")
          compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
          // Host tests run on the JVM against a stubbed android.jar.
          withHostTest { isReturnDefaultValues = true }
        }

        iosArm64()
        iosSimulatorArm64()

        compilerOptions {
          freeCompilerArgs.add("-Xexpect-actual-classes")
          optIn.add("kotlin.time.ExperimentalTime")
        }

        sourceSets.getByName("commonMain").dependencies {
          implementation(libs.library("kotlinx-coroutines-core"))
        }
        sourceSets.getByName("commonTest").dependencies {
          implementation(libs.library("kotlin-test"))
          implementation(libs.library("kotlinx-coroutines-test"))
        }
      }
    }
}

internal val Project.kotlinMultiplatform: KotlinMultiplatformExtension
  get() = extensions.getByType()
