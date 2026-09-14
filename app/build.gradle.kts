import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 서명 정보는 저장소에 넣지 않는다. keystore.properties 가 없으면 서명 없이 빌드한다.
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.artbrain.ebwo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.artbrain.ebwo"
        minSdk = 30        // Poke4 Lite = Android 11
        targetSdk = 36
        versionCode = 19
        versionName = "0.2.10"
    }

    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 따로 둔 키가 없으면 디버그 키로 서명한다 — 이 앱은 처음부터 디버그 키
            // (SHA-1 9F:26:8E…E3:32)로 배포했고 구글 OAuth 도 그 키로 등록돼 있다.
            // 0.2.10 부터 줄이기(R8)를 켠 정식 빌드로 배포한다 — APK 18.7MB → 수 MB.
            signingConfig = if (signing.isNotEmpty()) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // PdfBox 가 데려오는 BouncyCastle 의 양자내성 암호 표(8MB)는 쓸 일이 없어 뺀다.
    packaging {
        resources {
            excludes += "org/bouncycastle/pqc/**"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // txt·md·srt·epub 의 한글 인코딩을 알아맞힌다 (EUC-KR·MS949 가 흔하다).
    implementation(libs.juniversalchardet)
    // PDF 에서 글을 뽑는다 (30EBSE 와 같은 Pdf.kt).
    implementation(libs.pdfbox.android)

    testImplementation(libs.junit)
    implementation(libs.play.services.auth)
}
