import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.roadinspection"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.roadinspection"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val localProperties = Properties()
        val localFile = rootProject.file("local.properties")

        if (localFile.exists()) {
            localProperties.load(FileInputStream(localFile))
        }

        // 获取定义的 URL
        val serverUrl = localProperties.getProperty("server.url", "\"http://localhost:3000\"")

        // 生成 BuildConfig
        buildConfigField("String", "SERVER_URL", serverUrl)
    }

    signingConfigs {
        create("commonConfig") {
            // 指向你刚刚放入项目中的文件
            storeFile = file("debug.keystore")
            // 默认 debug 证书的密码通常都是 "android"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            // 2. 在 debug 模式下强制使用这个固定签名
            signingConfig = signingConfigs.getByName("commonConfig")
        }
        getByName("release") {
            // release 模式通常也建议使用固定的签名
            signingConfig = signingConfigs.getByName("commonConfig")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.gson)
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.amap.api:3dmap-location-search:10.1.600_loc6.5.1_sea9.7.4")
    implementation("org.greenrobot:eventbus:3.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("com.aliyun.dpa:oss-android-sdk:2.9.21")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}

// === 粘贴在 app/build.gradle.kts 的最末尾 ===

tasks.register("findSoFile") {
    doLast {
        println("\n========== 🕵️‍♂️ KTS版(最终修正)：开始全库搜查 libTransform.so 🕵️‍♂️ ==========")

        val config = project.configurations.findByName("debugRuntimeClasspath")

        config?.files?.forEach { file ->
            try {
                if (file.name.endsWith(".aar") || file.name.endsWith(".jar")) {
                    // 修正点：visit 后面直接跟花括号，不要写 'details ->'
                    project.zipTree(file).visit {
                        // 在这里，'this' 就是文件详情对象
                        // 直接访问 'name' 和 'relativePath' 属性即可
                        if (this.name.contains("libTransform.so")) {
                            println("\n🔥🔥🔥 抓到了！🔥🔥🔥")
                            println("藏身之处 (库名):  ${file.name}")
                            println("文件详细路径: ${file.absolutePath}")
                            println("SO文件内部路径: ${this.relativePath}")
                            println("----------------------------------------------")
                        }
                    }
                }
            } catch (e: Exception) {
                // 忽略读取错误的包
            }
        }
        println("========== 搜查结束 ==========\n")
    }
}