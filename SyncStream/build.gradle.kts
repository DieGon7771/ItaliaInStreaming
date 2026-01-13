plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

android {
    namespace = "it.dogior.syncstream"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
        
        // BuildConfig semplificato - solo info base
        buildConfigField("String", "APP_NAME", "\"SyncStream\"")
        buildConfigField("String", "VERSION_NAME", "\"1.0.0\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    
    // CloudStream dependency
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}

// Versione del plugin
version = 1

cloudstream {
    description = "Organizza homepage e sincronizza Continue Watching tra dispositivi"
    authors = listOf("RowdyRushya, Phisher98, DieGon") 
    
    /**
     * Status:
     * 0: Down
     * 1: Ok  
     * 2: Slow
     * 3: Beta only
     */
    status = 3 // Imposta a 1 (Ok) quando è stabile
    
    tvTypes = listOf("All")
    
    requiresResources = true
    language = "it"
    
    // Icona per SyncStream (dovrai crearne una!)
    iconUrl = "https://raw.githubusercontent.com/iltuonome/tuorepo/main/icons/syncstream.png"
    
    isCrossPlatform = false
}
