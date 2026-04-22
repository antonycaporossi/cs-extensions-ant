import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import com.lagradost.cloudstream3.gradle.CloudstreamExtension


buildscript {
    repositories {
        google()
        mavenCentral()
        // Shitpack repo which contains our tools and dependencies
        maven("https://jitpack.io")
    }

    /*configurations.all {
        resolutionStrategy {
            force("com.github.vidstige:jadb:9083b5096f")
        }
    }*/

    dependencies {
        classpath("com.android.tools.build:gradle:9.1.0")
        // Cloudstream gradle plugin which makes everything work and builds plugins
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()


fun Project.android(configuration: LibraryExtension.() -> Unit) {
    extensions.getByName<LibraryExtension>("android").apply {
        project.extensions.findByType(JavaPluginExtension::class.java)?.apply {
            // Use Java 17 toolchain even if a higher JDK runs the build.
            // We still use Java 8 for now which higher JDKs have deprecated.
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
        }

        configuration()
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "user/repo")
    }

    android {
        namespace = "it.csextensionsant"
        compileSdk = 36

        defaultConfig {
            minSdk = 21
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-Xannotation-default-target=param-property"
                )
            }
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations


        // Stubs for all Cloudstream classes
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // these dependencies can include any of those which are added by the app,
        // but you dont need to include any of them if you dont need them
        // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle
        implementation(kotlin("stdlib")) // adds standard kotlin features
        implementation("com.github.Blatzar:NiceHttp:0.4.17") // http library
        implementation("org.jsoup:jsoup:1.22.1") // html parser
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2") // html parser
        implementation("com.github.vidstige:jadb:v1.2.1")

        //run JS
        implementation("org.mozilla:rhino:1.9.1")
		    // Library/extensions searching with Levenshtein distance
        implementation ("me.xdrop:fuzzywuzzy:1.4.0")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}