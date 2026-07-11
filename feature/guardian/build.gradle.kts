plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.cereveil.feature.guardian"
  compileSdk = 36

  defaultConfig {
    minSdk = 26
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(libs.clerk.android.api)
  implementation(project(":core:domain"))
  implementation(project(":core:network"))
}
