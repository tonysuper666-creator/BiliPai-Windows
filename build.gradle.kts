// 根目录 build.gradle.kts
// R8 单独强制为 9.4 稳定版：AGP 9.3.2 内嵌的 R8 9.3.x 存在类合并优化 bug
// （VideoCard 组合函数被合并进 HeightInLinesModifierKt 后生成 ART 校验失败的
// 字节码，启动即 VerifyError）。Studio 2026.1.3 最高支持 AGP 9.3.0，无法通过
// 升级 AGP 获得 R8 修复，故沿用 buildscript classpath 覆盖方式（同此前 9.1.31 pin）。

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools:r8:9.4.27")
    }
}

plugins {
    // 1. Android 插件 (版本号要固定)
    // AGP ≥ 9.1 required by Compose BOM 2026.06 / Material3 alpha25 / Lifecycle 2.11 / Nav3 1.2
    id("com.android.application") version "9.3.2" apply false
    id("com.android.library") version "9.3.2" apply false
    id("com.android.test") version "9.3.2" apply false

    id("com.google.devtools.ksp") version "2.3.10" apply false

    // 2. Kotlin 插件（AGP 9+ 内置 Kotlin，无需 org.jetbrains.kotlin.android）
    // Compose 编译器插件
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    // 序列化插件
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false

    // 3. Firebase 相关插件
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.crashlytics") version "3.0.2" apply false
}
