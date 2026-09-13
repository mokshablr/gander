import com.android.build.api.artifact.SingleArtifact

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arjun.gander"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arjun.gander"
        minSdk = 26
        targetSdk = 36
        versionCode = 19
        versionName = "1.17"
    }

    // The release keystore is intentionally not in the repo. Contributors without
    // it get an unsigned release build; debug builds always work.
    //
    // The password is read from GANDER_STORE_PASSWORD / GANDER_KEY_PASSWORD, which
    // belong in ~/.gradle/gradle.properties rather than here, because this file is
    // public and a password committed to it is a password published. The fallback
    // is the throwaway one the README tells contributors to generate their own key
    // with, so cloning and building keeps working with no configuration at all.
    val releaseKeystore = rootProject.file("keystore/gander.jks")
    if (releaseKeystore.exists()) {
        val storePass = (findProperty("GANDER_STORE_PASSWORD") as String?) ?: "gander-local"
        val keyPass = (findProperty("GANDER_KEY_PASSWORD") as String?) ?: storePass
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = storePass
                keyAlias = "gander"
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            // The app is 1,700 lines of Kotlin; the dex was 14.8 MB, nearly all of it
            // library code that nothing here calls. R8 keeps the reachable part and
            // drops the rest, and shrinkResources does the same for the resource table.
            // Kept in the open, so obfuscation buys no secrecy: what it buys is the
            // shrinking and optimisation that come with it. See proguard-rules.pro for
            // why line numbers survive it.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // So a debug build installs alongside an existing release install rather
            // than being refused for having a different signing key, which would
            // otherwise mean uninstalling and losing recents and folder grants.
            applicationIdSuffix = ".debug"
        }
    }

    // No per-ABI split: with PDF rendering moved off Pdfium the app ships no native
    // code at all, so one APK serves every architecture.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Requesting nothing is the whole promise, but permissions arrive transitively: Media3 contributes
// ACCESS_NETWORK_STATE, stripped in the manifest. Naming one permission there does not stop the next
// dependency bump adding another, and that would surface in the store listing rather than the build.
// So assert the invariant on the merged manifest instead of trusting the strip.
//
// Held as suffixes on the variant's own applicationId, since a debug build carries
// one and would otherwise fail against a hardcoded package name.
val permissionAllowlistSuffixes = setOf(
    // androidx.core declares this so libraries can registerReceiver(..., RECEIVER_NOT_EXPORTED).
    // Signature level and self-granted, so it is never shown to the user as a permission.
    ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
)

androidComponents.onVariants { variant ->
    val suffix = variant.name.replaceFirstChar { it.uppercase() }
    val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
    val appId = variant.applicationId

    val checkPermissions = tasks.register("check${suffix}Permissions") {
        description = "Fails if the merged manifest requests any permission we did not sign off on."
        val manifestFile = mergedManifest
        val allowedSuffixes = permissionAllowlistSuffixes
        val applicationId = appId
        val stamp = layout.buildDirectory.file("reports/permissions/$suffix.txt")
        inputs.file(manifestFile)
        outputs.file(stamp)
        doLast {
            val requested = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
                .findAll(manifestFile.get().asFile.readText())
                .map { it.groupValues[1] }
                .toList()
            val allowed = allowedSuffixes.map { applicationId.get() + it }.toSet()
            val unexpected = requested.filterNot { it in allowed }
            if (unexpected.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("Gander ships with no permissions, but $suffix requests:")
                        unexpected.forEach { appendLine("    $it") }
                        appendLine()
                        appendLine("A dependency added these. Either strip each one with")
                        appendLine("tools:node=\"remove\" in AndroidManifest.xml, or add it to")
                        append("permissionAllowlist in app/build.gradle.kts with a reason.")
                    }
                )
            }
            stamp.get().asFile.apply {
                parentFile.mkdirs()
                writeText(requested.joinToString("\n"))
            }
        }
    }

    // Variant tasks are not registered yet while onVariants runs, so match lazily.
    //
    // Both outputs, and that is the point. assemble builds the APK that goes to
    // GitHub releases; bundle builds the AAB that goes to Play, and it does not
    // depend on assemble. Guarding only the first left the store upload, the one
    // build whose permission list is read by users as a promise, unchecked.
    tasks.matching { it.name == "assemble$suffix" || it.name == "bundle$suffix" }
        .configureEach { dependsOn(checkPermissions) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.16.0")
    // Zoomable image view that tiles huge bitmaps
    implementation("com.davemorrissey.labs:subsampling-scale-image-view-androidx:3.10.0")
    // EXIF orientation for photos opened via SAF content URIs
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Video and audio playback
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
