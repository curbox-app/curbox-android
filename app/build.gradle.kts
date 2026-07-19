import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// The fastlane changelogs are the single source of truth: the store listings, the
// GitHub release body and the in-app "what's new" dialog all read the same files.
// They are copied into assets/changelogs/<versionCode>.txt at build time.
abstract class CopyFastlaneChangelogsTask : DefaultTask() {
    @get:InputDirectory
    abstract val changelogs: DirectoryProperty

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun copyChangelogs() {
        val target = assetsDir.get().dir("changelogs").asFile
        target.deleteRecursively()
        target.mkdirs()
        changelogs.get().asFile.listFiles()
            ?.filter { it.extension == "txt" }
            ?.forEach { it.copyTo(target.resolve(it.name), overwrite = true) }
    }
}

val copyFastlaneChangelogs = tasks.register<CopyFastlaneChangelogsTask>("copyFastlaneChangelogs") {
    changelogs.set(rootProject.layout.projectDirectory.dir("fastlane/metadata/android/en-US/changelogs"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyFastlaneChangelogs,
            CopyFastlaneChangelogsTask::assetsDir
        )
    }
}

android {
    namespace = "neth.iecal.curbox"
    compileSdk = 35
    flavorDimensions += "version"

    lint {
        disable.add("NullSafeMutableLiveData")
        abortOnError = false
    }

    defaultConfig {
        applicationId = "neth.iecal.curbox"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "Curbox")

        // Every flavor ships every feature unless it opts out below.
        buildConfigField("Boolean", "SUPPORTS_UI_HIDER", "true")
        buildConfigField("Boolean", "SUPPORTS_ANTI_UNINSTALL", "true")
        // Holds WRITE_SECURE_SETTINGS (granted once via Shizuku) so grayscale can
        // flip secure settings directly instead of shelling out per toggle.
        buildConfigField("Boolean", "SUPPORTS_WRITE_SECURE_SETTINGS", "true")
    }

    productFlavors {
        // Fully offline, otherwise every feature except cross device sync.
        create("fdroid") {
            dimension = "version"
            versionNameSuffix = "-fdroid"
            buildConfigField("Boolean", "FDROID_VARIANT", "true")
        }

        // Everything: sync plus all blocker features.
        create("full") {
            dimension = "version"
            versionNameSuffix = "-full"
            buildConfigField("Boolean", "FDROID_VARIANT", "false")
            // Staging switch for the FCM push migration. While false, the realtime
            // websocket + poll stay on (no regression) and FCM only adds background
            // delivery. Flip to true once FCM is verified end to end: realtime is
            // then dropped and the poll slows right down, which is where the memory
            // and battery win lands.
            buildConfigField("Boolean", "SYNC_USE_FCM", "true")
        }

        // Play Store policy build: keeps sync but strips the UI hider and anti
        // uninstall (no device admin), see src/playstore/AndroidManifest.xml.
        create("playstore") {
            dimension = "version"
            versionNameSuffix = "-playstore"
            buildConfigField("Boolean", "FDROID_VARIANT", "false")
            buildConfigField("Boolean", "SYNC_USE_FCM", "true")
            buildConfigField("Boolean", "SUPPORTS_UI_HIDER", "false")
            buildConfigField("Boolean", "SUPPORTS_ANTI_UNINSTALL", "false")
            buildConfigField("Boolean", "SUPPORTS_WRITE_SECURE_SETTINGS", "false")
        }
    }

    sourceSets {
        // Cross device sync code shared by the full and playstore flavors.
        // F-Droid never compiles it, so that build stays offline.
        getByName("full") { java.srcDir("src/sync/java") }
        getByName("playstore") { java.srcDir("src/sync/java") }
    }


    splits {
        abi {
            isEnable = false

        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // required because of hardcoded f-droid values
            applicationVariants.all {
                val variant = this
                if (variant.flavorName == "fdroid") {
                    variant.outputs
                        .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
                        .forEach { output ->
                            val outputFileName = "app-fdroid-universal-release-unsigned.apk"
                            println("OutputFileName: $outputFileName")
                            output.outputFileName = outputFileName
                        }
                }
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Debug Curbox")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }
}



dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)


    // QR Scanner & Generator
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    
    // Shizuku dependecies
    implementation (libs.api)
    implementation (libs.provider)

    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)

    // Cross device sync. These live only in the full and playstore flavors so the
    // F-Droid build compiles with no network, no auth, and no Supabase code at all.
    "fullImplementation"(libs.okhttp)
    "fullImplementation"(libs.androidx.security.crypto)
    "fullImplementation"(libs.androidx.work.runtime.ktx)
    "playstoreImplementation"(libs.okhttp)
    "playstoreImplementation"(libs.androidx.security.crypto)
    "playstoreImplementation"(libs.androidx.work.runtime.ktx)

    // FCM push, sync flavors ONLY, so the phone can sync instantly in the
    // background without holding a websocket open. F-Droid never pulls in Firebase
    // or Google Play Services. Initialised manually from FcmConfig, so the
    // google-services Gradle plugin and google-services.json are NOT required.
    "fullImplementation"(platform("com.google.firebase:firebase-bom:33.5.1"))
    "fullImplementation"("com.google.firebase:firebase-messaging")
    "playstoreImplementation"(platform("com.google.firebase:firebase-bom:33.5.1"))
    "playstoreImplementation"("com.google.firebase:firebase-messaging")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.mpandroidchart)
    implementation(libs.timerangepicker)

}
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        tasks.register("installAndGrantAccessibility$variantName") {
            group = "install"
            description = "Installs the app, grants Accessibility permission, and launches it"
            dependsOn("install$variantName")
            
            doLast {
                val adbPath = sdkComponents.adb.get().asFile.absolutePath
                val appId = variant.applicationId.get()
                Thread.sleep(2000)
                // Grant Accessibility Permission
                exec {
                    val baseId = "neth.iecal.curbox"
                    val services = "$appId/$baseId.services.AppBlockerService"

                    commandLine(adbPath, "shell", "settings", "put", "secure", "enabled_accessibility_services", services)
                }

                // Launch MainActivity
                exec {
                    val baseId = "neth.iecal.curbox"
                    commandLine(adbPath, "shell", "am", "start", "-n", "$appId/$baseId.ui.activity.FragmentActivity")
                }
            }
        }
    }
}
