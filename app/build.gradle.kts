import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.angbang852.manjiao"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.angbang852.manjiao"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    // ★ 签名密钥出库：密码此前明文提交在仓库中（git 历史已暴露，建议尽快 rotate）。
    // 现从 local.properties 读取（该文件在 .gitignore 内），需包含：
    //   manjiao.storeFile=D:/path/to/release-manjiao.keystore
    //   manjiao.storePassword=xxx
    //   manjiao.keyAlias=manjiao
    //   manjiao.keyPassword=xxx
    // keystore 缺失时 release 构建产出 unsigned APK（不再因路径失效直接报错）
    val signingProps = Properties().apply {
        val f = rootProject.file("local.properties")
        // UTF-8 Reader：storeFile 路径含中文，Properties.load(InputStream) 默认
        // ISO-8859-1 会乱码导致 exists() 恒 false
        if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
    }
    signingConfigs {
        create("release") {
            val sf = signingProps.getProperty("manjiao.storeFile") ?: ""
            if (sf.isNotEmpty() && file(sf).exists()) {
                storeFile = file(sf)
                storePassword = signingProps.getProperty("manjiao.storePassword") ?: ""
                keyAlias = signingProps.getProperty("manjiao.keyAlias") ?: "manjiao"
                keyPassword = signingProps.getProperty("manjiao.keyPassword") ?: ""
            }
        }
    }
    buildTypes {
        release {
            // ★ R8 开启：Xposed 模块按名反射点已由 proguard-rules.pro 保护
            //（Module 入口 / libxposed API / JNI native 方法）
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

/**
 * 版本自适应逻辑的离线验证台（开发期用，不进产物）。
 *
 * 跑的是 app 模块**已编译**的 AdaptVerify.main —— 用真实实现断言，
 * 避免「测试副本与实现漂移」。见 `adapt/AdaptVerify.kt` 的说明。
 *
 * 用法：`gradlew :app:adaptVerify`
 */
tasks.register<JavaExec>("adaptVerify") {
    group = "verification"
    description = "验证版本自适应逻辑（档位映射 / 结构特征匹配 / 版本名解析）"
    dependsOn("compileDebugKotlin")
    // 用 debug 变体的 classes + 运行时依赖（含 kotlin-stdlib）
    val debugVariant = "debug"
    classpath = files(
        layout.buildDirectory.dir("tmp/kotlin-classes/$debugVariant"),
        configurations.getByName("${debugVariant}RuntimeClasspath")
    )
    mainClass.set("io.github.angbang852.manjiao.adapt.AdaptVerify")
}

dependencies {
    compileOnly(files("libs/libxposed-api-102.0.0.jar"))
    implementation(files("libs/libxposed-interface-102.0.0.jar"))
    implementation(files("libs/libxposed-service-102.0.0.jar"))

    // ★ DexKit 结构发现（2026-09）：官方坐标 org.luckypray:dexkit（2.0 起 artifactId
    // 由 DexKit 改为 dexkit），运行时按方法特征找混淆类（播放器），抗混淆/插件化
    implementation("org.luckypray:dexkit:2.2.0")

    implementation("androidx.core:core-ktx:1.13.1")
    // AppCompat：SettingsActivity 继承 AppCompatActivity（Manifest 的
    // Theme.SlowKick 亦继承 MaterialComponents，二者都需要）
    implementation("androidx.appcompat:appcompat:1.7.0")
    // ★ 依赖清理（审阅 2026-09 · L1）：仅移除 preference-ktx —— 全项目零引用
    //（设置页是自绘面板，未用 PreferenceFragment/PreferenceScreen）。
    //
    // ★ recyclerview 经实测**不能移除**：`material:1.12.0` 传递依赖
    //   androidx.recyclerview:1.0.0 + androidx.viewpager2:1.0.0，
    //   移除后离线构建立刻失败（checkReleaseAarMetadata 无法解析这两个传递依赖）。
    //   代码里确实没用 RecyclerView（grep 仅 3 处命中，全是注释），
    //   但它由 Material 主题链条带入，属于「被动依赖」——要真去掉得连 Material 一起换掉主题，
    //   收益（少量 dex）不抵风险，故保留。
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
}