import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 版本名可以重置，版本码始终递增，保证覆盖安装
 *
 * 0.2.2 (156)：UI 入口补齐版（媒体 NowPlaying/封面测试版 + HUD/语音键/GPS +
 * 98386 音频 48K key + 98311 saveArea + 设置开关接线 + 功能证据文档）。
 */
val appVersionName = "0.2.2"
val appVersionCode = 156

/**
 * 从根目录的 keystore.properties 读取签名，缺少时使用 debug 签名
 * 正式发布前配置自己的密钥，密钥和密码不入库
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps =
    Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "io.github.shaonialife.buicklinkplus"
    // 保持当前 SDK 版本
    //noinspection GradleDependency
    compileSdk = 34
    // 固定原生库裁剪工具版本，保持构建结果一致
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "io.github.shaonialife.buicklinkplus"
        minSdk = 31
        //noinspection OldTargetApi
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "BUILD_ID", "\"buick-$appVersionName-$appVersionCode\"")
        // Android 正则兼容用例使用设备测试
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // 只打包手机常用 ABI，去掉 x86/x86_64 以缩小 APK；模拟器验证需单独构建
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 反射和 AIDL 的保留规则未补齐，暂不开混淆
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
        debug {
            // debug 沿用正式包名，保持与正式包一致的安装/数据行为
            versionNameSuffix = "-debug"
        }
        // buick 使用独立包名，方便与原版并存
        create("buick") {
            initWith(getByName("release"))
            applicationIdSuffix = ".buick"
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "Buick Link +")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        // 关于页直接读取构建版本
        buildConfig = true
    }

    testOptions {
        // 本地 JVM 单测里的 android.jar 是 stub：CarLifeHuClient 等类会调用
        // android.os.SystemClock.uptimeMillis()，不开这个开关会抛
        // "Method ... not mocked"，假引擎集成测试（FakeCarLifeEngineTest）无法运行。
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        // 排除密码库中重复的 JVM OSGi 描述
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        // 本工程只用 bcprov 的基础算法（AES/Ed25519/X25519/SHA/HKDF），
        // 未使用 PQC（Picnic 查表约 1.2MB）和证书路径审查文案，运行时不需要这些资源
        resources.excludes += "org/bouncycastle/pqc/**"
        resources.excludes += "org/bouncycastle/x509/CertPathReviewerMessages*"
        // bcpkix 已移除；防御性排除其包资源（若将来被间接引入也不会进包）
        resources.excludes += "org/bouncycastle/pkix/**"
    }

    lint {
        // 检查全部 Lint 问题，不使用基线
        abortOnError = true
        warningsAsErrors = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    // 依赖固定版本，许可见 docs/dependencies.md
    //noinspection GradleDependency,NewerVersionAvailable
    implementation("androidx.core:core-ktx:1.13.1")
    //noinspection GradleDependency,NewerVersionAvailable
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 车机桌面分页
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    //noinspection GradleDependency,NewerVersionAvailable
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // AirPlay 配对和加密（AES/HKDF/Ed25519/X25519/ChaCha20-Poly1305）
    //noinspection GradleDependency,NewerVersionAvailable
    implementation("org.bouncycastle:bcprov-jdk18on:1.81.1")

    testImplementation("junit:junit:4.13.2")
    // 为 JVM 测试提供 JSON 实现
    //noinspection GradleDependency,NewerVersionAvailable
    testImplementation("org.json:json:20240303")
    //noinspection GradleDependency,NewerVersionAvailable
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    //noinspection GradleDependency,NewerVersionAvailable
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
}
