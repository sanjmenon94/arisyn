import java.util.Properties
import java.io.File

plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose); alias(libs.plugins.ksp) }

val localSecrets = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
val supabaseUrl: String = localSecrets.getProperty("SUPABASE_URL", "")
val supabaseAnonKey: String = localSecrets.getProperty("SUPABASE_ANON_KEY", "")
val signingPropertiesFile = File(System.getenv("USERPROFILE"), ".training\\release\\signing.properties")
val signingProperties = Properties().apply { signingPropertiesFile.takeIf { it.exists() }?.inputStream()?.use { load(it) } }
android { namespace = "com.training.app"; compileSdk = 35
    defaultConfig { applicationId = "com.training.app"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0"; buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\""); buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"") }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("release") {
            if (signingPropertiesFile.exists()) {
                storeFile = file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release"); isMinifyEnabled = false } }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.androidx.core); implementation(libs.activity.compose); implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui); implementation(libs.compose.ui.tooling.preview); implementation(libs.compose.material3); implementation(libs.compose.icons)
    implementation(libs.lifecycle.runtime); implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.room.runtime); implementation(libs.room.ktx); ksp(libs.room.compiler)
    implementation(libs.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.health.connect.client)
    debugImplementation(libs.compose.ui.tooling)
}
