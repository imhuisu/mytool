plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/* 서명 키는 저장소 밖(이 PC)에 둔다. 같은 키로 서명해야 폰에서 덮어 설치(업데이트)가 된다.
 * 키 위치·비밀번호: ~/.gradle/gradle.properties 의 MYTOOL_STORE_FILE / MYTOOL_STORE_PASS */
val storeFilePath = (findProperty("MYTOOL_STORE_FILE") as String?)
val storePass = (findProperty("MYTOOL_STORE_PASS") as String?)

android {
    namespace = "com.imhuisu.mytool.sched"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.imhuisu.mytool.sched"
        minSdk = 26
        targetSdk = 35
        versionCode = 3          // 올릴 때마다 +1, ../../app/version.json 도 같이
        versionName = "1.2"
    }

    signingConfigs {
        create("release") {
            if (storeFilePath != null && storePass != null) {
                storeFile = file(storeFilePath)
                storePassword = storePass
                keyAlias = "sched"
                keyPassword = storePass
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
