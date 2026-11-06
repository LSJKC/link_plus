plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("com.diffplug.spotless") version "7.2.1"
}

// Kotlin 格式使用 ktlint，规则见 .editorconfig
spotless {
    // 统一使用 LF，避免 Windows 改写整库换行
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    kotlin {
        target("app/src/**/*.kt")
        ktlint("1.3.1")
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint("1.3.1")
    }
}
