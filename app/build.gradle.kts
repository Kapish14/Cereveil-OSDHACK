import java.util.Properties

val localEnv =
  Properties().apply {
    val envFile = rootProject.file(".env.local")
    if (envFile.exists()) {
      envFile.inputStream().use(::load)
    }
  }

fun envValue(name: String): String = localEnv.getProperty(name) ?: System.getenv(name).orEmpty()

fun com.android.build.api.dsl.VariantDimension.buildConfigString(name: String, value: String) {
  val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
  buildConfigField("String", name, "\"$escaped\"")
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cereveil"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cereveil"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigString("CONVEX_URL", envValue("CONVEX_URL"))
        buildConfigString("CONVEX_SITE_URL", envValue("CONVEX_SITE_URL"))
    }

    flavorDimensions += "role"
    productFlavors {
        create("guardian") {
            dimension = "role"
            applicationIdSuffix = ".guardian.dev"
            versionNameSuffix = "-guardian-dev"
            resValue("string", "app_name", "Cereveil Guardian")
            buildConfigString("CEREVEIL_ROLE", "guardian")
            buildConfigString("CLERK_PUBLISHABLE_KEY", envValue("CLERK_PUBLISHABLE_KEY"))
            buildConfigString("FIREBASE_APPLICATION_ID", envValue("FIREBASE_GUARDIAN_APPLICATION_ID"))
            buildConfigString("FIREBASE_API_KEY", envValue("FIREBASE_API_KEY"))
            buildConfigString("FIREBASE_PROJECT_ID", envValue("FIREBASE_PROJECT_ID"))
            buildConfigString("FIREBASE_GCM_SENDER_ID", envValue("FIREBASE_GCM_SENDER_ID"))
            resValue("string", "google_maps_key", envValue("GOOGLE_MAPS_API_KEY"))
        }
        create("child") {
            dimension = "role"
            applicationIdSuffix = ".child.dev"
            versionNameSuffix = "-child-dev"
            resValue("string", "app_name", "Cereveil Child")
            buildConfigString("CEREVEIL_ROLE", "child")
            buildConfigString("CLERK_PUBLISHABLE_KEY", "")
            buildConfigString("FIREBASE_APPLICATION_ID", envValue("FIREBASE_CHILD_APPLICATION_ID"))
            buildConfigString("FIREBASE_API_KEY", envValue("FIREBASE_API_KEY"))
            buildConfigString("FIREBASE_PROJECT_ID", envValue("FIREBASE_PROJECT_ID"))
            buildConfigString("FIREBASE_GCM_SENDER_ID", envValue("FIREBASE_GCM_SENDER_ID"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      resValues = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  add("guardianImplementation", libs.androidx.compose.material.icons.extended)
  implementation(libs.compose.unstyled)
  implementation(libs.compose.interaction.capabilities)
  implementation(libs.ripple.indication)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  implementation(libs.convex.android) {
    isTransitive = true
  }
  implementation(libs.kotlinx.serialization.json)
  add("guardianImplementation", libs.clerk.android.api)
  add("guardianImplementation", libs.clerk.android.ui)
  add("guardianImplementation", libs.zxing.core)
  add("guardianImplementation", platform(libs.firebase.bom))
  add("guardianImplementation", libs.firebase.messaging)
  add("guardianImplementation", libs.play.services.maps)
  add("childImplementation", libs.google.code.scanner)
  add("childImplementation", platform(libs.firebase.bom))
  add("childImplementation", libs.firebase.messaging)
  add("childImplementation", libs.androidx.work.runtime.ktx)
  add("childImplementation", libs.webrtc.android)
  add("guardianImplementation", libs.webrtc.android)

  implementation(project(":core:domain"))
  implementation(project(":core:network"))
  add("guardianImplementation", project(":feature:guardian"))
  add("childImplementation", project(":feature:child"))
  add("childImplementation", project(":feature:child:ml"))
}

tasks.register<Copy>("stageHackathonApks") {
  group = "distribution"
  description = "Builds and stages the Guardian Mode and Child Mode APKs for a GitHub Release."

  dependsOn("assembleGuardianDebug", "assembleChildDebug")

  from(layout.buildDirectory.file("outputs/apk/guardian/debug/app-guardian-debug.apk")) {
    rename { "Cereveil-Guardian.apk" }
  }
  from(layout.buildDirectory.file("outputs/apk/child/debug/app-child-debug.apk")) {
    rename { "Cereveil-Child.apk" }
  }
  into(layout.buildDirectory.dir("outputs/hackathon"))
}
