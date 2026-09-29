import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "com.spacewire.meratune"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.spacewire.meratune"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${localProperties.getProperty("supabase.url", "https://lltfhcsmojzoxpewjrfk.supabase.co")}\""
        )
        buildConfigField(
            "String",
            "SUPABASE_KEY",
            "\"${localProperties.getProperty("supabase.key", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImxsdGZoY3Ntb2p6b3hwZXdqcmZrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQ2MzA3OTgsImV4cCI6MjEwMDIwNjc5OH0.1d9_pF7QpDMWV2BYE5WgzgBLB7KqlCinIzWTQm9XjNU")}\""
        )
        buildConfigField(
            "String",
            "MIXPANEL_TOKEN",
            "\"${localProperties.getProperty("mixpanel.token", "787ca4c84c0464f985c0d7ccbbc6a79b")}\""
        )

        val facebookAppId = localProperties.getProperty("facebook.app_id", "")
        val facebookClientToken = localProperties.getProperty("facebook.client_token", "")
        resValue("string", "facebook_app_id", facebookAppId)
        resValue("string", "facebook_client_token", facebookClientToken)
        if (facebookAppId.isNotEmpty()) {
            resValue("string", "fb_login_protocol_scheme", "fb$facebookAppId")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(platform("io.github.jan-tennert.supabase:bom:${libs.versions.supabase.get()}"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.ktor:ktor-client-content-negotiation:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${libs.versions.ktor.get()}")
    implementation(libs.ktor.client.okhttp)

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.material)
    implementation("com.cashfree.pg:api:2.4.0")
    implementation("com.google.android.gms:play-services-auth-api-phone:18.1.0")
    implementation("com.mixpanel.android:mixpanel-android:7.5.4")
    implementation("com.android.installreferrer:installreferrer:2.2")
    implementation("com.facebook.android:facebook-core:18.+")
    implementation(platform("com.google.firebase:firebase-bom:${libs.versions.firebaseBom.get()}"))
    implementation("com.google.firebase:firebase-analytics")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
