plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

dependencies {
    // JSON：Mojang 版本 JSON / Modrinth / CurseForge 响应解析
    api(libs.jackson.databind)
    // SLF4J 门面；具体实现由 :app 提供（保持 core 纯净、可离线单测）
    api(libs.slf4j.api)
    implementation(libs.jackson.core)
    implementation(libs.jackson.annotations)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    // 测试运行时必须使用与生产一致的 SLF4J -> JUL 桥接，
    // 否则 LogManager 的 JUL handler 收不到记录，日志相关断言将失真
    testRuntimeOnly(libs.slf4j.jdk14)
}

// ---------------------------------------------------------------------------
// 版本号：唯一改动点是根目录 gradle.properties 里的 misaka.version。
//
// core 不能依赖 :app 的 AppInfo（模块方向相反），所以把版本号生成成一份资源随 core
// 一起分发，运行时的 BuildInfo 从 classpath 读它 —— core 与 app 因此拿到同一个值，
// 不需要任何一处再写死版本号。
// ---------------------------------------------------------------------------
val versionResourceDir = layout.buildDirectory.dir("generated/version-resources")

val generateVersionResource = tasks.register("generateVersionResource") {
    group = "misaka"
    description = "把 gradle.properties 的 misaka.version 写成资源，供 BuildInfo 读取"
    val version = rootProject.version.toString()
    val outputDir = versionResourceDir
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("misaka-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$version\n", Charsets.UTF_8)
    }
}

sourceSets.named("main") {
    // 目录由上面的任务产出；显式 dependsOn 保证 processResources 一定跑在它之后
    resources.srcDir(versionResourceDir)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateVersionResource)
}

// ---------------------------------------------------------------------------
// 构建期凭据：仓库里永远不存 Client ID / CurseForge API Key
// ---------------------------------------------------------------------------
// 取值优先级（高 → 低）：
//   1) 环境变量  MISAKA_MS_CLIENT_ID / MISAKA_CURSEFORGE_API_KEY   ← CI（GitHub Secrets）
//   2) Gradle 属性 misaka.microsoftClientId / misaka.curseforgeApiKey
//      （本地写 ~/.gradle/gradle.properties，不要写进仓库里的那份）
//   3) 空串 —— 正版登录不可用、CurseForge 来源自动退回 Modrinth
//
// 生成的是 **Java 源码**而不是资源文件：值会变成编译期常量，
// 运行期没有任何配置入口能覆盖它（这正是「不允许外部读取」的要求）。
// 生成目录在 build/ 下，天然被 .gitignore 排除。
// ---------------------------------------------------------------------------
val secretsSourceDir = layout.buildDirectory.dir("generated/secrets")

val generateBuildSecrets = tasks.register("generateBuildSecrets") {
    group = "misaka"
    description = "把构建期注入的 Client ID / CurseForge Key 生成为编译期常量"

    val clientId = providers.environmentVariable("MISAKA_MS_CLIENT_ID")
        .orElse(providers.gradleProperty("misaka.microsoftClientId"))
        .orElse("")
    val curseForgeKey = providers.environmentVariable("MISAKA_CURSEFORGE_API_KEY")
        .orElse(providers.gradleProperty("misaka.curseforgeApiKey"))
        .orElse("")

    // 凭据随时可能变（CI 上每次都是新的环境变量），不做增量判断
    outputs.upToDateWhen { false }

    doLast {
        // 只做最小转义：值本身是 GUID / 类似 $2a$10$... 的字符串，不该含换行与引号
        fun literal(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "")
            .replace("\n", "")

        val id = clientId.get().trim()
        val key = curseForgeKey.get().trim()
        val target = secretsSourceDir.get()
            .file("com/misaka/launcher/core/config/BuildSecrets.java").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            package com.misaka.launcher.core.config;

            /**
             * 构建期注入的凭据。
             *
             * <p><b>这个文件不在源码里</b>：由 Gradle 任务 {@code generateBuildSecrets} 生成到
             * {@code core/build/generated/secrets/}，仓库永远不包含它。注入方式见
             * {@code docs/BUILD-SECRETS.md}。</p>
             *
             * <p>值编译进 class 常量，运行期没有任何配置入口可覆盖 —— 这是刻意的：
             * 允许运行时覆盖意味着拿到发行版的人可以把它换成别人的应用登记。</p>
             */
            public final class BuildSecrets {

                /** 微软 OAuth 应用的 Client ID；未注入时为空串（正版登录不可用）。 */
                public static final String MICROSOFT_CLIENT_ID = "${literal(id)}";

                /** CurseForge API Key；未注入时为空串（该来源自动退回 Modrinth）。 */
                public static final String CURSEFORGE_API_KEY = "${literal(key)}";

                private BuildSecrets() {
                }
            }

            """.trimIndent(),
            Charsets.UTF_8,
        )

        logger.lifecycle(
            "构建期凭据：Client ID {}，CurseForge Key {}",
            if (id.isEmpty()) "未注入（正版登录不可用）" else "已注入",
            if (key.isEmpty()) "未注入（该来源退回 Modrinth）" else "已注入",
        )
    }
}

sourceSets.named("main") {
    // 生成目录要在编译之前存在，因此显式声明编译依赖
    java.srcDir(secretsSourceDir)
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateBuildSecrets)
}
