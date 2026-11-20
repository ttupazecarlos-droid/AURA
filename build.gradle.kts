// ARCHIVO: build.gradle.kts (raíz)
// FUNCIÓN: Configuración Gradle común a todos los módulos. Declara los plugins disponibles
//          (Android, Compose y KSP) sin aplicarlos aquí.
// NAVEGACIÓN: settings.gradle.kts -> build.gradle.kts -> app/build.gradle.kts
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
}
