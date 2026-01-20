import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.13.0")  // SUA VERSIONE
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")  // SUA VERSIONE
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")  // LUI USA 2.3.0
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

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/doGior/doGiorsHadEnough")
    }

    android {
        namespace = "it.dogior.hadEnough"
        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
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
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations
        
        cloudstream("com.lagradost:cloudstream3:pre-release")  // LUI USA PRE-RELEASE

        // COPIA ESATTAMENTE LE SUE DIPENDENZE
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.16")  // SUA VERSIONE
        implementation("org.jsoup:jsoup:1.22.1")             // SUA VERSIONE
        implementation("androidx.annotation:annotation:1.9.1")  // AGGIUNGI
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")  // SUA VERSIONE
        implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")  // SUA VERSIONE
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")  // AGGIUNGI
        implementation("org.mozilla:rhino:1.9.0")  // AGGIUNGI
        implementation("me.xdrop:fuzzywuzzy:1.4.0")  // AGGIUNGI
        implementation("com.google.code.gson:gson:2.13.2")  // SUA VERSIONE
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")  // AGGIUNGI
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
