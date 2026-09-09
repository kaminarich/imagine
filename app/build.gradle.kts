plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.kaminari.imagine'
    compileSdk 34

    defaultConfig {
        applicationId "com.kaminari.imagine"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "1.0"

    externalNativeBuild {
        cmake {
            cppFlags "-std=c++17 -fexceptions"
            arguments "-DANDROID_STL=c++_shared"
        }
    }
        ndk {
            abiFilters 'arm64-v8a', 'armeabi-v7a'
        }
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
            version "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }

    buildFeatures {
        compose true
    }

    composeOptions {
        kotlinCompilerExtensionVersion '1.5.10'
    }
}

dependencies {
    implementation platform('androidx.compose:compose-bom:2024.02.00')
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.material3:material3'
    implementation 'androidx.compose.ui:ui-tooling-preview'
    implementation 'androidx.activity:activity-compose:1.8.2'
    implementation 'androidx.lifecycle:lifecycle-runtime-compose:2.7.0'
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'com.google.accompanist:accompanist-permissions:0.34.0'
    testImplementation 'junit:junit:4.13.2'
    debugImplementation 'androidx.compose.ui:ui-tooling'
}
