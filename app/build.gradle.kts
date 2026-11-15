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

    // Libraries ship strings for about 80 languages. Keep only the ones Curbox translates.
    androidResources {
        localeFilters += listOf("en", "ar", "de", "es", "it", "ja", "pt-rBR", "ru", "tr", "vi", "zh-rCN")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/**/LICENSE.txt",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/com/android/build/gradle/app-metadata.properties",
                "kotlin/**",
                "kotlin-tooling-metadata.json",
                "DebugProbesKt.bin"
            )
        }
    }

    defaultConfig {
        applicationId = "neth.iecal.curbox"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "7"
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
        // Fully offline.
        create("fdroid") {
            dimension = "version"
            versionNameSuffix = "-fdroid"
            buildConfigField("Boolean", "FDROID_VARIANT", "true")
        }

        // Fully offline.
        create("full") {
            dimension = "version"
            versionNameSuffix = "-full"
            buildConfigField("Boolean", "FDROID_VARIANT", "true")
        }

        // Play Store policy build: offline, strips the UI hider and anti
        // uninstall (no device admin), see src/playstore/AndroidManifest.xml.
        create("playstore") {
            dimension = "version"
            versionNameSuffix = "-playstore"
            buildConfigField("Boolean", "FDROID_VARIANT", "true")
            buildConfigField("Boolean", "SUPPORTS_UI_HIDER", "false")
            buildConfigField("Boolean", "SUPPORTS_ANTI_UNINSTALL", "false")
            buildConfigField("Boolean", "SUPPORTS_WRITE_SECURE_SETTINGS", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.mpandroidchart)

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
