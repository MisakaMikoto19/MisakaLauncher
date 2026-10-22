import java.security.MessageDigest
import java.time.OffsetDateTime
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    application
}

/**
 * JavaFX 采用「平台分类器 + 显式依赖」方式引入，不依赖 javafxplugin：
 *  - 显式声明可完全走国内镜像，构建行为可预期；
 *  - 平台分类器由当前构建机 OS/Arch 推导，跨平台打包时在对应平台构建即可。
 */
val fxVersion: String = libs.versions.javafx.get()
val appVersion: String = version.toString()

val mainClassName = "com.misaka.launcher.app.MainLauncher"

/**
 * 产品标识：jpackage 的 `--name`、分发包目录名、产物文件名前缀、安装目录名全部由它派生。
 * 只用一处字面量，避免改名时漏掉某个产物名。
 */
val appId: String = "MisakaLauncher"

val buildOs: String = System.getProperty("os.name").lowercase()
val buildArch: String = System.getProperty("os.arch").lowercase()
val isWindows: Boolean = buildOs.contains("win")

val fxClassifier: String = when {
    buildOs.contains("win") -> "win"
    buildOs.contains("mac") || buildOs.contains("darwin") ->
        if (buildArch.contains("aarch64") || buildArch.contains("arm64")) "mac-aarch64" else "mac"
    else ->
        if (buildArch.contains("aarch64") || buildArch.contains("arm64")) "linux-aarch64" else "linux"
}

/** 分发目录后缀，产出统一落在项目根目录的 build_<os>/ 下 */
val osKey: String = when {
    buildOs.contains("win") -> "windows"
    buildOs.contains("mac") || buildOs.contains("darwin") -> "macos"
    else -> "linux"
}

/**
 * jpackage 生成的 app-image 目录名。
 *
 * <p>macOS 上 jpackage 产出的是 **bundle**（`$appId.app`），
 * 而不是 Windows/Linux 那样的同名目录。早期代码把它硬编码成目录名，
 * 结果 macOS 上这个目录永远不存在，Gradle 在执行前的输入校验就直接失败
 * （"specifies directory ... which doesn't exist"）。</p>
 */
val appImageName: String =
    if (buildOs.contains("mac") || buildOs.contains("darwin")) "$appId.app"
    else appId

// media 必须带上平台分类器：javafx-web 会传递引入无分类器的空壳 media 包，
// 缺失真实实现时 WebView 在首次使用媒体能力时会抛 NoClassDefFoundError。
val fxModules = listOf("base", "graphics", "controls", "fxml", "media", "web")

/**
 * 仅构建期使用的类路径：把设计稿（icon.webp）解码为 PNG。
 * 单独建一个 configuration 而不是写进 implementation —— 它只服务于 generateIcons 这个
 * 构建期任务，不应被打进分发包（运行期只读构建期转好的 PNG）。
 */
val iconToolClasspath: Configuration = configurations.create("iconToolClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isVisible = false
}

dependencies {
    implementation(project(":core"))
    fxModules.forEach { m ->
        implementation("org.openjfx:javafx-$m:$fxVersion:$fxClassifier")
    }
    // 日志桥接：SLF4J -> java.util.logging，由 core 的 LogManager 统一接管输出
    runtimeOnly(libs.slf4j.jdk14) {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    iconToolClasspath(libs.twelvemonkeys.imageio.webp)
}

application {
    // JavaFX 位于 classpath 时，主类不能直接继承 Application，需经由启动包装类
    mainClass.set(mainClassName)
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dmisaka.launcher.root=.",
    )
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.named<Jar>("jar") {
    // 固定文件名，jpackage 的 --main-jar 需要稳定名称
    archiveFileName.set("misaka-app.jar")
    manifest {
        attributes(
            "Main-Class" to mainClassName,
            "Implementation-Title" to "Misaka Launcher",
            "Implementation-Version" to appVersion,
        )
    }
}

// ======================================================================
// 自带运行时与分发包
// ======================================================================

/**
 * 用于 jlink/jpackage 的 JDK。
 * 优先使用工程声明的 Java 17 工具链（同一 JDK 同时提供 javac 与 jmods），
 * 保证「编译产物 - 运行时镜像 - 安装包」三者 JDK 一致。
 */
val jdkHome: String = run {
    val toolchains = extensions.getByType(JavaToolchainService::class.java)
    val launcher = toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
    launcher.get().metadata.installationPath.asFile.absolutePath
}

val jdkBin = "$jdkHome/bin/"

/** 启动器自身运行所需的 JDK 模块（JavaFX 以 classpath 方式随包分发，无需额外 jmods）。 */
val jlinkModuleSet = listOf(
    "java.base",
    "java.desktop",
    "java.logging",
    "java.net.http",
    "java.naming",
    "java.scripting",
    "java.sql",
    "java.xml",
    "java.security.jgss",
    "jdk.charsets",
    "jdk.crypto.cryptoki",
    "jdk.crypto.ec",
    "jdk.jsobject",
    "jdk.localedata",
    // 读取物理内存（com.sun.management.OperatingSystemMXBean）需要该模块，
    // 缺失时启动参数预设将无法感知机器内存而退化为保守默认值
    "jdk.management",
    "jdk.unsupported",
    "jdk.zipfs",
    // 浏览器登录要用本地回环服务器接住微软的回调（com.sun.net.httpserver 在这个模块里）。
    // 缺失时的现象极具迷惑性：任务体抛的是 Error 而非 Exception，
    // 界面只会停在「正在准备登录…」不动，日志里什么都没有（已踩过）。
    "jdk.httpserver",
)

val runtimeImageDir = layout.buildDirectory.dir("misaka/runtime")
val appLibDir = layout.buildDirectory.dir("misaka/app-lib")
val jpackageWorkDir = layout.buildDirectory.dir("misaka/jpackage")
val generatedIconsDir = layout.buildDirectory.dir("generated/images")
val distAppDir = rootProject.layout.projectDirectory.dir("build_$osKey/$appId")

/**
 * 运行期数据目录：从分发目录里运行应用时，这些目录会被就地创建
 * （`java/` 安装一个 JDK 就有数百 MB，`config/` 里是用户设置与账户）。
 *
 * <p>打包时必须排除。`misakaDist` 是增量任务 —— 本地调试写过这些目录之后，
 * 它可能判定「无需更新」而跳过清理，于是这些内容会被原样打进发布包：
 * 单文件包体积暴涨（实测 81.7 MB → 259.5 MB），还会把本机配置与日志一起发出去。</p>
 */
val runtimeDataDirs = listOf(
    "java", "downloads", "config", "instances", "logs", "cache", "temp", "backgrounds", ".minecraft",
)

/** 图标设计稿（项目根目录）。构建期转成 PNG/ICO/ICNS；缺失时退回程序化绘制。 */
val iconArtwork = rootProject.layout.projectDirectory.file("icon.webp")

/**
 * 用 IconGenerator 生成图标（PNG / ICO / ICNS）。
 *
 * 来源优先级：项目根目录的 icon.webp → 程序化绘制（沿用产品设计语言）。
 * 转换必须发生在构建期：JavaFX 与 JDK 自带 ImageIO 都**不支持 WebP**，
 * 运行期读 .webp 只会得到空图且不报错（详见 IconGenerator 的类注释）。
 */
val generateIcons = tasks.register<JavaExec>("generateIcons") {
    group = "misaka"
    description = "生成应用图标（PNG/ICO/ICNS；来源：项目根目录 icon.webp）"
    // 只依赖编译而非 classes：生成的 PNG 还要作为资源参与 processResources，
    // 若依赖 classes 就会与 processResources 形成循环依赖
    dependsOn(tasks.named("compileJava"))
    classpath = files(
        sourceSets["main"].output.classesDirs,
        configurations.runtimeClasspath,
        iconToolClasspath,
    )
    mainClass.set("com.misaka.launcher.tooling.IconGenerator")
    val outputDir = generatedIconsDir.get().asFile
    args = listOf(outputDir.absolutePath, iconArtwork.asFile.absolutePath)
    // 设计稿是可选的：删掉它应当仍能构建（退回程序化图标），而不是让打包失败
    inputs.file(iconArtwork).withPropertyName("iconArtwork").optional()
    inputs.dir(layout.buildDirectory.dir("classes/java/main"))
    outputs.dir(outputDir)
}

/** 生成的图标 PNG 落到应用资源目录，运行期窗口 / 任务栏图标从 jar 内读取。 */
val iconResourceDir = layout.buildDirectory.dir("generated/iconResource")

val copyIconResource = tasks.register<Copy>("copyIconResource") {
    group = "misaka"
    description = "把图标 PNG 复制为应用资源（窗口 / 任务栏图标）"
    dependsOn(generateIcons)
    from(generatedIconsDir)
    include("icon-*.png")
    into(iconResourceDir.map { it.dir("icons") })
}

// 让 processResources 把生成的图标一并打进应用 jar；界面通过
// /icons/icon-<尺寸>.png 读取（见 AppIcon）
sourceSets["main"].resources.srcDir(iconResourceDir)
tasks.named("processResources") { dependsOn(copyIconResource) }

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    group = "misaka"
    description = "用 jlink 生成启动器自带的最小 Java 运行时镜像"
    val outDir = runtimeImageDir.get().asFile
    inputs.property("modules", jlinkModuleSet.joinToString(","))
    inputs.property("jdk", jdkHome)
    outputs.dir(outDir)
    doFirst {
        outDir.deleteRecursively()
        outDir.parentFile.mkdirs()
    }
    executable = (if (isWindows) jdkBin + "jlink.exe" else jdkBin + "jlink")
    args = listOf(
        "--module-path", "$jdkHome/jmods",
        "--add-modules", jlinkModuleSet.joinToString(","),
        "--output", outDir.absolutePath,
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
        "--compress=2",
    )
}

val prepareAppLib = tasks.register<Sync>("prepareAppLib") {
    group = "misaka"
    description = "把应用主 jar 与全部运行时依赖收集到待打包目录"
    into(appLibDir)
    from(tasks.named<Jar>("jar"))
    from(configurations.runtimeClasspath)
}

val packageDist = tasks.register<Exec>("packageDist") {
    group = "misaka"
    description = "用 jpackage 生成自带运行时的应用镜像（免安装版）"
    dependsOn(jlinkRuntime, prepareAppLib, generateIcons)

    val destDir = jpackageWorkDir.get().asFile
    inputs.dir(appLibDir)
    inputs.dir(runtimeImageDir)
    outputs.dir(destDir)

    doFirst {
        destDir.deleteRecursively()
        destDir.mkdirs()
    }

    executable = (if (isWindows) jdkBin + "jpackage.exe" else jdkBin + "jpackage")
    args = buildList {
        add("--type"); add("app-image")
        add("--name"); add(appId)
        add("--app-version"); add(appVersion)
        add("--vendor"); add(providers.gradleProperty("misaka.vendor").get())
        add("--description"); add("Misaka Launcher")
        add("--dest"); add(destDir.absolutePath)
        add("--input"); add(appLibDir.get().asFile.absolutePath)
        add("--main-jar"); add("misaka-app.jar")
        add("--main-class"); add(mainClassName)
        add("--runtime-image"); add(runtimeImageDir.get().asFile.absolutePath)
        add("--java-options"); add("-Dfile.encoding=UTF-8")
        add("--java-options"); add("-XX:+UseG1GC")
        add("--java-options"); add("-XX:MaxGCPauseMillis=100")

        // 图标由 generateIcons 任务生成，且它是本任务的依赖，
        // 因此执行到此处时文件必然存在（不要配置期做 exists 判断，
        // 那时生成任务尚未运行，会导致图标被静默跳过）
        val iconName = if (isWindows) "icon.ico" else if (buildOs.contains("mac")) "icon.icns" else "icon.png"
        add("--icon"); add(generatedIconsDir.get().asFile.resolve(iconName).absolutePath)
    }
}

/**
 * 把 jpackage 产物复制到项目根目录的 build_-os- 目录，并在根目录写入安装根标记文件，
 * 使 PortablePaths.detectRoot() 能从 lib/app 下的 jar 反向定位到安装根目录。
 *
 * 注意：Kotlin 支持嵌套块注释，KDoc 中不可出现连续的斜杠加星号，
 * 否则会额外开启一层注释并吞掉其后的全部代码。
 */
/** 按当前平台选择 jpackage 的安装包类型。 */
val installerType: String = when {
    isWindows -> "msi"
    buildOs.contains("mac") -> "dmg"
    else -> "deb"
}

val packageInstaller = tasks.register<Exec>("packageInstaller") {
    group = "misaka"
    description = "用 jpackage 生成平台安装包（Windows: msi / Linux: deb / macOS: dmg）"
    dependsOn(jlinkRuntime, prepareAppLib, generateIcons)

    val destDir = layout.buildDirectory.dir("misaka/installer").get().asFile
    inputs.dir(appLibDir)
    inputs.dir(runtimeImageDir)
    outputs.dir(destDir)

    doFirst {
        destDir.deleteRecursively()
        destDir.mkdirs()
    }

    executable = (if (isWindows) jdkBin + "jpackage.exe" else jdkBin + "jpackage")
    args = buildList {
        add("--type"); add(installerType)
        add("--name"); add(appId)
        add("--app-version"); add(appVersion)
        add("--vendor"); add(providers.gradleProperty("misaka.vendor").get())
        add("--description"); add("Misaka Launcher")
        add("--dest"); add(destDir.absolutePath)
        add("--input"); add(appLibDir.get().asFile.absolutePath)
        add("--main-jar"); add("misaka-app.jar")
        add("--main-class"); add(mainClassName)
        add("--runtime-image"); add(runtimeImageDir.get().asFile.absolutePath)
        add("--java-options"); add("-Dfile.encoding=UTF-8")
        val iconName = if (isWindows) "icon.ico" else if (buildOs.contains("mac")) "icon.icns" else "icon.png"
        add("--icon"); add(generatedIconsDir.get().asFile.resolve(iconName).absolutePath)
        // 每个平台只加各自支持的参数，避免 jpackage 因未知参数直接失败
        if (isWindows) {
            add("--win-dir-chooser")
            add("--win-shortcut")
        }
        if (buildOs.contains("mac")) {
            add("--mac-package-name"); add(appId)
        }
        if (!isWindows && !buildOs.contains("mac")) {
            add("--linux-shortcut")
        }
    }

    doLast {
        val targetDir = rootProject.layout.projectDirectory.dir("build_$osKey").asFile
        targetDir.mkdirs()
        destDir.listFiles()?.forEach { artifact ->
            copy {
                from(artifact)
                into(targetDir)
            }
        }
        logger.lifecycle("安装包已生成到 ${targetDir.absolutePath}")
    }
}

/**
 * 用打包自带的运行时做一次启动自检。
 * <p>这是「安装包真的能跑起来」的唯一低成本验证方式：不依赖目标机器是否装了 Java，
 * 因此可以在 CI 上直接执行。</p>
 */
val smokeTest = tasks.register<Exec>("smokeTest") {
    group = "misaka"
    description = "用自带运行时执行 --dump-info 自检"
    dependsOn(misakaDist)

    val distRoot = distAppDir.asFile
    doFirst {
        val javaExe = findFile(distRoot) { candidate ->
            (candidate.name == "java" || candidate.name == "java.exe") && candidate.parentFile?.name == "bin"
        } ?: throw GradleException("分发目录中找不到自带运行时: $distRoot")
        val libraryDir = findFile(distRoot) { it.name == "misaka-app.jar" }?.parentFile
            ?: throw GradleException("分发目录中找不到应用 jar: $distRoot")
        logger.lifecycle("自检使用运行时: $javaExe")
        // 必须把「安装根目录」指到构建目录：便携模式是默认值，自检一旦按真实布局解析，
        // 就会把配置 / 实例 / 日志写进分发目录，并最终被打进发布包（所有用户共享同一份
        // 预生成的实例与账户）。指到构建目录后，自检产物随 clean 一并消失。
        val smokeHome = layout.buildDirectory.dir("misaka/smoke-home").get().asFile
        smokeHome.mkdirs()
        commandLine(
            javaExe.absolutePath,
            "-Dfile.encoding=UTF-8",
            "-Dmisaka.launcher.root=${smokeHome.absolutePath}",
            "-cp", "${libraryDir.absolutePath}${File.separator}*",
            "com.misaka.launcher.app.MainLauncher", "--dump-info",
        )
    }
}

/** 在目录树中查找首个满足条件的文件。 */
fun findFile(root: File, predicate: (File) -> Boolean): File? =
    root.walkTopDown().firstOrNull { it.isFile && predicate(it) }

/** 十六进制 SHA-256。流式读取，避免把上百 MB 的包一次性读进内存。 */
fun sha256Hex(file: File): String {
    // 注意：Kotlin 构建脚本里不能写 java.security.MessageDigest ——
    // `java` 会被解析成 Gradle 的 java 扩展而不是包名，必须走 import
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * 负载指纹：SHA-256 的前 8 位十六进制。
 * <p>单文件外壳用它判断「本地缓存是否还是当前这份负载」。若只比对版本号，
 * 同一版本号重新构建后外壳会认为缓存依然有效，用户会一直看到上一个版本的界面。</p>
 */
fun payloadFingerprint(file: File): String = sha256Hex(file).take(8)

val misakaDist = tasks.register("misakaDist") {
    group = "misaka"
    description = "生成三平台分发包到项目根目录 build_<os>/MisakaLauncher"
    // 注意：不能命名为 assembleDist —— application 插件已占用该名称，
    // 重名会导致本任务被惰性注册后被静默忽略，打包链路不会真正执行。
    dependsOn(packageDist)

    val builtApp = jpackageWorkDir.get().asFile.resolve(appImageName)
    val target = distAppDir.asFile

    inputs.dir(builtApp)
    outputs.dir(target)

    doLast {
        if (!builtApp.isDirectory) {
            throw GradleException("未找到 jpackage 产物目录: $builtApp")
        }
        target.deleteRecursively()
        target.mkdirs()
        if (appImageName.endsWith(".app")) {
            // macOS：必须保留 bundle 目录本身（.app 一旦被拆开就不能双击运行），
            // 所以复制到 target 下的同名子目录，而不是把 Contents 直接摊到 target 里
            copy {
                from(builtApp)
                into(File(target, appImageName))
            }
        } else {
            copy {
                from(builtApp)
                into(target)
            }
        }
        // 安装根标记：PortablePaths 依赖它把安装根目录与 lib/app 区分开
        File(target, ".misaka-root").writeText("MisakaLauncher root marker")
        logger.lifecycle("分发包已生成: ${target.absolutePath}")
    }
}

// ======================================================================
// 单文件发布包
// ======================================================================
//
// 目标：发布产物是「一个文件」，双击即可运行，且自带运行时（用户无需预装 Java）。
//
// 实现取舍（重要，勿随意替换）：
//   * jpackage 的 exe/msi 需要目标机器安装 WiX Toolset，deb/dmg 需要对应平台工具链，
//     都不是「零依赖」方案；
//   * 7-Zip 的自解压「安装器」模块（7zS.sfx / 7zSD.sfx）不在标准安装包里，
//     需要额外下载 7-Zip Extra；
//   * 因此 Windows 用系统自带的 csc.exe（.NET Framework 4.x）编译一个自解压外壳，
//     Linux/macOS 用 POSIX 自解压脚本（脚本 + 尾部拼接 tar.gz）。
//
// 两种形态的共同点：只在首次运行解压（解压到用户数据目录并写入完成标记），
// 之后启动不再解压，因此第二次起与免安装版一样快。

val singleFileWorkDir = layout.buildDirectory.dir("misaka/singlefile")
val singleFileOutDir = rootProject.layout.projectDirectory.dir("build_$osKey").asFile
val singleFileName = "$appId-$appVersion-portable" + (if (isWindows) ".exe" else ".sh")

/** 打包单文件负载：Windows 用 zip（.NET 自带解压），其余平台用 tar.gz。 */
val singleFilePayload = tasks.register<Zip>("singleFilePayload") {
    group = "misaka"
    description = "为单文件外壳准备 payload.zip（Windows）"
    dependsOn(misakaDist)
    onlyIf { isWindows }

    from(distAppDir)
    destinationDirectory.set(singleFileWorkDir)
    archiveFileName.set("payload.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    runtimeDataDirs.forEach { exclude("$it/**") }
    exclude("**/._*", "**/.DS_Store")
}

val singleFilePayloadTar = tasks.register<Tar>("singleFilePayloadTar") {
    group = "misaka"
    description = "为自解压脚本准备 payload.tar.gz（Linux/macOS）"
    dependsOn(misakaDist)
    onlyIf { !isWindows }

    from(distAppDir)
    destinationDirectory.set(singleFileWorkDir)
    archiveFileName.set("payload.tar.gz")
    compression = Compression.GZIP
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    runtimeDataDirs.forEach { exclude("$it/**") }
    exclude("**/._*", "**/.DS_Store")
}

/** Windows：用系统自带 csc 生成自解压单文件 exe。 */
val singleFileWindows = tasks.register<Exec>("singleFileWindows") {
    group = "misaka"
    description = "用系统自带 csc 编译 Windows 自解压单文件（无需 WiX）"
    dependsOn(singleFilePayload, generateIcons)
    onlyIf { isWindows }

    val work = singleFileWorkDir.get().asFile
    val payload = File(work, "payload.zip")
    val versionFile = File(work, "version.txt")
    val source = rootProject.layout.projectDirectory
        .file("packaging/windows/SingleFileLauncher.cs").asFile
    val icon = generatedIconsDir.get().asFile.resolve("icon.ico")
    val outFile = File(singleFileOutDir, singleFileName)
    // .NET Framework 的编译器固定在这个位置，且与 csc 同目录就有所需的框架程序集
    val frameworkDir = File(System.getenv("SystemRoot") ?: "C:\\Windows",
        "Microsoft.NET\\Framework64\\v4.0.30319")

    inputs.file(payload)
    inputs.file(source)
    outputs.file(outFile)

    doFirst {
        if (!source.isFile) {
            throw GradleException("缺少自解压外壳源码: $source")
        }
        val csc = File(frameworkDir, "csc.exe")
        if (!csc.isFile) {
            throw GradleException("未找到 csc.exe（需要 .NET Framework 4.x）: $csc")
        }
        versionFile.writeText("$appVersion+${payloadFingerprint(payload)}")
        outFile.parentFile.mkdirs()
        commandLine(
            csc.absolutePath,
            "/nologo",
            "/target:winexe",
            // 源码是 UTF-8：不显式指定代码页时 csc 会按系统 ANSI 读取，中文提示会变乱码
            "/codepage:65001",
            "/out:${outFile.absolutePath}",
            "/reference:System.IO.Compression.dll",
            "/reference:System.IO.Compression.FileSystem.dll",
            "/reference:System.Windows.Forms.dll",
            "/reference:System.Drawing.dll",
            "/win32icon:${icon.absolutePath}",
            "/resource:${payload.absolutePath},payload.zip",
            "/resource:${versionFile.absolutePath},version.txt",
            source.absolutePath,
        )
    }

    doLast {
        logger.lifecycle("单文件发布包已生成: ${outFile.absolutePath}"
                + "（${outFile.length() / 1024 / 1024} MB）")
    }
}

/** Linux / macOS：脚本头 + 尾部拼接 payload.tar.gz。 */
val singleFileUnix = tasks.register("singleFileUnix") {
    group = "misaka"
    description = "生成 Linux/macOS 自解压单文件（sh + 尾部负载）"
    dependsOn(singleFilePayloadTar)

    val work = singleFileWorkDir.get().asFile
    val payload = File(work, "payload.tar.gz")
    val header = rootProject.layout.projectDirectory
        .file("packaging/unix/SelfExtractHeader.sh").asFile
    val outFile = File(singleFileOutDir, singleFileName)
    val launchRelative = if (buildOs.contains("mac"))
        "$appId.app/Contents/MacOS/$appId"
    else "bin/$appId"

    inputs.files(payload, header)
    outputs.file(outFile)

    onlyIf { !isWindows }

    doLast {
        if (!header.isFile) {
            throw GradleException("缺少自解压脚本头: $header")
        }
        val text = header.readText(Charsets.UTF_8)
            .replace("@VERSION@", appVersion)
            .replace("@LAUNCH_RELATIVE@", launchRelative)
        outFile.parentFile.mkdirs()
        outFile.outputStream().use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            payload.inputStream().use { input -> input.copyTo(out) }
        }
        outFile.setExecutable(true, false)
        logger.lifecycle("单文件发布包已生成: ${outFile.absolutePath}"
                + "（${outFile.length() / 1024 / 1024} MB）")
    }
}

/** 统一入口：按平台选择对应的单文件生成方式。 */
val packageSingleFile = tasks.register("packageSingleFile") {
    group = "misaka"
    description = "生成单文件发布包（Windows 自解压 exe / Linux·macOS 自解压 sh）"
    if (isWindows) {
        dependsOn(singleFileWindows)
    } else {
        dependsOn(singleFileUnix)
    }
}

// ======================================================================
// 发布打包：把发布产物集中到项目根目录 dist/
// ======================================================================
//
// 为什么把逻辑放在 Gradle 而不是写两份 shell 脚本：
//   * 「收集产物 + 算校验和 + 生成清单」是纯逻辑，三平台完全一致，
//     只有归档格式（zip / tar.gz）存在差异，没必要维护两份会逐渐走样的脚本；
//   * 平台薄封装脚本（release.ps1 / release.sh）只负责调用这里的任务并打印结果。
//
// 产物命名统一带平台标识，避免多平台产物在同一个 dist/ 里重名互相覆盖。

val distDir = rootProject.layout.projectDirectory.dir("dist")
val releaseBaseName = "$appId-$appVersion-$osKey"
val releaseWorkDir = layout.buildDirectory.dir("misaka/release")
val releaseArchiveName = "$releaseBaseName-portable" + (if (isWindows) ".zip" else ".tar.gz")

/** 免安装版归档：Windows 用 zip，其余平台用 tar.gz（tar 能保留可执行位）。 */
val releaseArchive = tasks.register<Zip>("releaseArchive") {
    group = "misaka"
    description = "把免安装版打成带顶层目录的 zip（Windows）"
    dependsOn(misakaDist)
    onlyIf { isWindows }

    // 刻意加一层顶层目录：解压后直接得到一个完整目录，
    // 而不是把上百个文件散落到用户当前目录里
    from(distAppDir) { into(releaseBaseName) }
    destinationDirectory.set(releaseWorkDir)
    archiveFileName.set(releaseArchiveName)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    runtimeDataDirs.forEach { exclude("$it/**") }
    exclude("**/._*", "**/.DS_Store")
}

val releaseArchiveTar = tasks.register<Tar>("releaseArchiveTar") {
    group = "misaka"
    description = "把免安装版打成带顶层目录的 tar.gz（Linux / macOS）"
    dependsOn(misakaDist)
    onlyIf { !isWindows }

    from(distAppDir) { into(releaseBaseName) }
    destinationDirectory.set(releaseWorkDir)
    archiveFileName.set(releaseArchiveName)
    compression = Compression.GZIP
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    runtimeDataDirs.forEach { exclude("$it/**") }
    exclude("**/._*", "**/.DS_Store")
}

/**
 * 是否是「发布产物」——只有这类文件才允许在收集新产物时被清理。
 *
 * <p>刻意用白名单而不是「除了运行期目录全删」：dist/ 里还有可能是用户自己放进去的
 * 东西（备份、快捷方式、说明），白名单能保证一个都不会被误删。</p>
 */
fun isReleaseArtifact(name: String): Boolean {
    val lower = name.lowercase()
    return lower == "readme.txt"
            || lower == "release.json"
            || lower == "sha256sums.txt"
            // 旧名（改名前）留下的产物也要能被清理，否则 dist/ 会长期留着两份
            || lower.startsWith("misakaminecraftlauncher-")
            // 站点固定名（MisakaLauncher-windows-x64.exe 等）也是本任务的产物：
            // 不加这一条，旧版本留下的同名文件永远不会被清理
            || lower.startsWith("misakalauncher-")
            || lower.endsWith(".msi")
            || lower.endsWith(".deb")
            || lower.endsWith(".dmg")
            || lower.endsWith(".tar.gz")
}

/**
 * 版本号一致性校验。
 *
 * <p>版本号只有一处可以改：根目录 {@code gradle.properties} 的 {@code misaka.version}。
 * 运行期/产物名/清单全部由它派生（见 core 的 {@code BuildInfo}），但有两处它管不到：
 * 手写的说明文档、以及有人图省事又在代码里写死一份。这个任务把这两类漏网拦在发布之前。</p>
 *
 * <p>挂在 {@link #releaseDist} 上，因此 {@code build_all.ps1} / {@code release.ps1} 每次发布
 * 都会执行；成本只是读几个文件，所以不参与增量判断（{@code upToDateWhen { false }}）。</p>
 */
val verifyVersion = tasks.register("verifyVersion") {
    group = "misaka"
    description = "校验版本号仍只有一处来源：说明文档已同步、源码里没有写死的版本号"
    val expected = appVersion
    val projectRoot = rootProject.projectDir
    inputs.property("version", expected)
    outputs.upToDateWhen { false }

    doLast {
        // 1) 源码里不允许再出现与当前版本号相同的字面量
        val literal = "\"$expected\""
        val sourceRoots = listOf(
            File(projectRoot, "core/src/main/java"),
            File(projectRoot, "app/src/main/java"),
        )
        val offenders = sourceRoots
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "java" }
                    .filter { it.readText(Charsets.UTF_8).contains(literal) }
                    .map { it.relativeTo(projectRoot).path }
                    .toList()
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "以下源码里写死了版本号 $expected，请改用 BuildInfo.version()（core）/ AppInfo.version()（app）：\n"
                        + offenders.joinToString("\n") { "  - $it" })
        }

        // 2) 说明文档是手写的，构建无法替换，只能要求它跟上
        val doc = File(projectRoot, "使用说明.md")
        if (!doc.isFile) {
            throw GradleException("找不到 使用说明.md（$doc）")
        }
        if (!doc.readText(Charsets.UTF_8).contains("v$expected")) {
            throw GradleException(
                "使用说明.md 的「适用版本」没有跟到 v$expected：这条是手写的，请手动同步后再发布")
        }
        logger.lifecycle("版本号校验通过：$expected（文档与源码均无写死的版本号）")
    }
}

/** 发布打包：收集产物到 dist/ 并生成校验文件与清单。 */
val releaseDist = tasks.register("releaseDist") {
    group = "misaka"
    description = "把单文件包、免安装版归档、交叉编译的 jar 与平台安装包收集到项目根目录 dist/"
    dependsOn(verifyVersion, packageSingleFile, releaseArchive, releaseArchiveTar, distJarAll)

    val outDir = distDir.asFile
    val releaseWork = releaseWorkDir.get().asFile
    val buildOutDir = singleFileOutDir
    val singleFile = File(buildOutDir, singleFileName)

    // 发布任务每次都要产出全新的 dist，不做增量判断
    outputs.upToDateWhen { false }

    doLast {
        if (!singleFile.isFile) {
            throw GradleException("未找到单文件发布包: $singleFile（请先执行 :app:packageSingleFile）")
        }
        outDir.mkdirs()
        // ⚠️ 这里**绝不能** deleteRecursively()：
        // 用户常常直接在 dist/ 里运行启动器（那是最省事的方式），
        // 于是 dist/ 里还躺着 .minecraft（几百 MB 游戏与模组）、java/（自带 JDK）、
        // config/（账户与设置）。整体清空会把它们一起永久删除 —— 真实发生过一次，
        // 回收站与卷影副本里都找不回来。
        // 因此只清理「本任务自己产出的文件」，其余一律不动。
        outDir.listFiles()?.forEach { artifact ->
            if (artifact.isFile && isReleaseArtifact(artifact.name)) {
                artifact.delete()
            }
        }
        val preserved = runtimeDataDirs.filter { File(outDir, it).exists() }
        if (preserved.isNotEmpty()) {
            logger.lifecycle("dist/ 中存在运行期数据（{}），已原样保留", preserved.joinToString(", "))
        }

        val collected = mutableListOf<File>()

        // 1) 单文件发布包
        singleFile.copyTo(File(outDir, releaseBaseName
                + (if (isWindows) "-portable.exe" else "-portable.sh")), overwrite = true)
            .let { collected += it }

        // 2) 免安装版归档
        val archive = File(releaseWork, releaseArchiveName)
        if (archive.isFile) {
            archive.copyTo(File(outDir, releaseArchiveName), overwrite = true).let { collected += it }
        }

        // 3) 交叉编译的可执行 jar：Windows 与 Linux 两个目标一起产出，
        //    与构建机平台无关（JavaFX 分类器显式指定，不带 JVM）
        jarDistTasks.forEach { jarTask ->
            val jar = jarTask.get().archiveFile.get().asFile
            if (jar.isFile) {
                jar.copyTo(File(outDir, jar.name), overwrite = true).let { collected += it }
                // 每个 jar 单独一份 .sha256：发布到更新服务是按单个文件登记校验值的。
                // 这里必须一起重建 —— 上面的「清理旧产物」会把它删掉（名字符合产物白名单）
                File(outDir, jar.name + ".sha256").writeText("${sha256Hex(jar)}  ${jar.name}\n")
            }
        }

        // 4) 平台安装包：只有执行过 :app:packageInstaller 才会存在
        val installerExtensions = setOf("msi", "deb", "dmg", "pkg", "rpm")
        buildOutDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in installerExtensions }
            ?.sortedBy { it.name }
            ?.forEach { installer ->
                installer.copyTo(File(outDir, installer.name), overwrite = true).let { collected += it }
            }

        // 5) 站点下载用的固定文件名
        //
        // 网站首页的下载按钮写的是固定名（MisakaLauncher-windows-x64.exe 等），
        // 这样发多少版都不用改站点代码。之前这一步靠人工复制，忘一次就是四个 404，
        // 所以放到构建里：产物一出来，固定名跟着就有。
        //
        // 注意保留带版本号的原件：更新服务是按「产品键 + 版本号 + 文件名」登记的，
        // 站点用固定名、更新服务用版本名，两套并存。
        val siteAliases = listOf(
            "$releaseBaseName-portable.exe" to "$appId-windows-x64.exe",
            "$appId-$appVersion-linux-amd64.jar" to "$appId-linux-x64.jar",
            "$appId-$appVersion-macos-amd64.jar" to "$appId-macos-x64.jar",
            "$appId-$appVersion-macos-aarch64.jar" to "$appId-macos-arm64.jar",
        )
        val aliased = mutableListOf<String>()
        siteAliases.forEach { (sourceName, aliasName) ->
            val source = File(outDir, sourceName)
            if (!source.isFile) {
                // 单文件包只在 Windows 构建时存在；跨平台 jar 在任意平台都有
                return@forEach
            }
            source.copyTo(File(outDir, aliasName), overwrite = true).let { collected += it }
            aliased += aliasName
        }
        if (aliased.isNotEmpty()) {
            logger.lifecycle("站点固定名资产：${aliased.joinToString(", ")}")
        }

        // 6) 校验和与清单
        val sorted = collected.sortedBy { it.name }
        File(outDir, "SHA256SUMS.txt").writeText(
            sorted.joinToString("") { "${sha256Hex(it)}  ${it.name}\n" })
        File(outDir, "release.json").writeText(renderReleaseManifest(sorted))
        File(outDir, "README.txt").writeText(releaseReadme())

        logger.lifecycle("发布产物已收集到: ${outDir.absolutePath}")
        sorted.forEach { artifact ->
            logger.lifecycle("  ${artifact.name}（${artifact.length() / 1024 / 1024} MB）")
        }
    }
}

/**
 * 构建时间戳（秒级）。
 * <p>同样不能写 {@code java.time.XxxYyy} —— 构建脚本里的 {@code java} 是 Gradle 的 java 扩展，
 * 必须走顶层 import。时间戳只精确到秒，便于同一批产物在清单里保持可读。</p>
 */
fun builtAt(): String = OffsetDateTime.now().withNano(0).toString()

/** 生成 release.json 清单（手写而非引入 JSON 库：构建脚本里这是最小依赖的做法）。 */
fun renderReleaseManifest(artifacts: List<File>): String {
    val entries = artifacts.joinToString(",\n") { artifact ->
        """    {
      "name": "${artifact.name}",
      "sizeBytes": ${artifact.length()},
      "sha256": "${sha256Hex(artifact)}"
    }"""
    }
    return """{
  "name": "Misaka Launcher",
  "version": "$appVersion",
  "platform": "$osKey",
  "arch": "$buildArch",
  "jdk": "${File(jdkHome).name}",
  "builtAt": "${builtAt()}",
  "artifacts": [
$entries
  ]
}
"""
}

/** 随包分发的简短说明：告诉拿到文件的人怎么用、数据放在哪。 */
fun releaseReadme(): String = """
Misaka Launcher $appVersion（$osKey）
构建时间：${builtAt()}

【单文件发布包】推荐分发这个
  ${releaseBaseName}${if (isWindows) "-portable.exe" else "-portable.sh"}
  双击/直接运行即可，目标机器无需预装 Java。
  首次运行会把自带运行时解压到用户目录（仅此一次，约 100 MB），之后启动是瞬时的：
    Windows : %LOCALAPPDATA%\MisakaLauncher\app\$appVersion
    Linux   : ${'$'}{XDG_DATA_HOME:-~/.local/share}/MisakaLauncher/app/$appVersion
    macOS   : ${'$'}{XDG_DATA_HOME:-~/.local/share}/MisakaLauncher/app/$appVersion
  删除上述目录即可卸载，实例、存档与配置不会受影响。

【免安装版归档】$releaseArchiveName
  解压后直接运行目录内的启动器，自带 Java 运行时，可整目录拷贝到别处使用。

【游戏用 Java】
  按 Minecraft 版本自动挑选并安装到启动器同级目录的 java/ 下，与启动器自身运行时互不影响。

【数据目录】
  Windows : %APPDATA%\MisakaLauncher
  macOS   : ~/Library/Application Support/MisakaLauncher
  Linux   : ${'$'}{XDG_DATA_HOME:-~/.local/share}/MisakaLauncher
  想做成绿色便携版：用设置页的「便携模式」开关即可（它会在启动器安装根目录写下
  misaka.portable 标记，之后配置、实例与 Java 运行时都放在安装目录内）。

【可执行 jar（交叉编译，三平台四目标一起产出）】目标机器需自备 Java 17
  MisakaLauncher-$appVersion-windows-amd64.jar    Windows x64
  MisakaLauncher-$appVersion-linux-amd64.jar      Linux x64
  MisakaLauncher-$appVersion-macos-amd64.jar      macOS Intel
  MisakaLauncher-$appVersion-macos-aarch64.jar    macOS Apple Silicon
  运行：java -jar <文件>（Windows 上双击亦可）。
  注意命名与数据取值不是一回事：
    - 产物名里的 amd64 / aarch64 是我们的习惯叫法；
    - 发布到更新服务（ZooKeeper）时 platform/arch 要填 windows/x64、linux/x64、
      darwin/x64、darwin/arm64 —— 平台名是 darwin（不是 macos），
      架构只有 x64/arm64 是合法取值。
  这类包不带 JVM，与单文件包是两种形态：要「目标机器不装 Java」，请用上面的单文件包。

【校验】
  SHA256SUMS.txt 为各产物的 SHA-256；Windows 可用
  `certutil -hashfile <文件名> SHA256` 自行核对。
"""

// ======================================================================
// 交叉编译：自带依赖的可执行 jar（Windows / Linux / macOS，四个目标）
// ======================================================================
//
// 为什么单独做这一套：
//   * 单文件 exe / sh 里塞的是 jlink 运行时，而 jlink 只能生成「构建机自己」的镜像，
//     在 Windows 上做不出 Linux / macOS 的免安装包 —— 这是工具链限制，不是配置问题；
//   * jar 不带 JVM，平台差异只剩 JavaFX 分类器（win / linux / mac / mac-aarch64），显式声明即可，
//     于是在同一台 Windows 机器上就能产出三个平台、四个目标的 jar（含 Apple Silicon）；
//   * 代价是目标机器要自备 Java 17。要求「目标机器不装 Java」时仍应分发单文件包，
//     两种形态适用场景不同，故在 README 里分开说明。
//
// 产物：dist/MisakaLauncher-<版本>-<目标>.jar 与同名 .sha256

/** 中间产物目录：与 build_<os>/、build/misaka/ 一样统一收在 build/ 下。 */
val jarDistDir: Provider<Directory> = layout.buildDirectory.dir("misaka/jars")

/**
 * 目标平台 → JavaFX 分类器。
 *
 * <p>两套命名不一样，写错会得到一个「跑起来就报缺 JavaFX」的 jar：
 * 我们的产物名用 {@code windows} / {@code macos}，JavaFX 的分类器用 {@code win} / {@code mac}；
 * 架构上我们沿用 {@code amd64} / {@code aarch64}，而更新服务要求的取值是 {@code x64} / {@code arm64}。</p>
 *
 * <p>macOS 两个架构都要产出：客户机既有 Apple Silicon（arm64）也有 Intel（x64），
 * 而客户端上报的 arch 正是 arm64/x64 —— 登记到更新服务时缺哪个架构，
 * 那部分客户机就永远收不到更新。macOS 单文件包只能在 macOS 上构建，
 * 但这两个 jar 在任意平台都能交叉编译出来，是 mac 客户机当前唯一可用的更新形态。</p>
 */
val jarDistTargets: Map<String, String> = linkedMapOf(
    "windows-amd64" to "win",
    "linux-amd64" to "linux",
    "macos-amd64" to "mac",
    "macos-aarch64" to "mac-aarch64",
)

/**
 * 注册一个目标的 jar 任务。
 * <p>依赖单独建一个 configuration 而不是复用 {@code runtimeClasspath}：
 * 后者按<b>构建机</b>平台解析 JavaFX，交叉编译时必须换成目标平台的分类器。</p>
 */
fun registerJarDist(targetName: String, fxTarget: String): TaskProvider<Jar> {
    val targetClasspath = configurations.create("jarDist-$targetName") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isVisible = false
    }
    dependencies.add(targetClasspath.name, project(":core"))
    fxModules.forEach { module ->
        dependencies.add(targetClasspath.name, "org.openjfx:javafx-$module:$fxVersion:$fxTarget")
    }
    dependencies.addProvider(targetClasspath.name, libs.slf4j.jdk14)

    return tasks.register<Jar>("jarDist-$targetName") {
        group = "misaka"
        description = "生成 $targetName 的自带依赖可执行 jar（可在任意平台交叉构建）"
        dependsOn(tasks.named("classes"))
        archiveFileName.set("$appId-$appVersion-$targetName.jar")
        destinationDirectory.set(jarDistDir)
        // 展开依赖必然撞上重复项（各 jar 都带自己的 MANIFEST / LICENSE），保留先出现的即可
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest {
            attributes(
                "Main-Class" to mainClassName,
                "Implementation-Title" to "Misaka Launcher",
                "Implementation-Version" to appVersion,
            )
        }
        // 启动器自己的类与资源（界面 CSS 在 app、i18n 与图标在 core 的 resources 里）
        from(sourceSets.main.get().output)
        // 依赖全部展开：core 的类与资源、Jackson、SLF4J 绑定、目标平台的 JavaFX 本地库；
        // 用 provider 包住而不是在此刻直接解析 —— 配置阶段解析会增加不必要的耦合
        from(provider { targetClasspath.map { if (it.isDirectory) it else zipTree(it) } })
        // 签名文件必须去掉：残留的旧签名会让 JVM 认为 jar 被篡改而拒绝加载；
        // module-info 一律去掉：这些依赖在 classpath 上运行，留着只会造成重复模块描述
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST")
        exclude("module-info.class", "META-INF/versions/*/module-info.class")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

val jarDistTasks: List<TaskProvider<Jar>> = jarDistTargets.map { (targetName, fxTarget) ->
    registerJarDist(targetName, fxTarget)
}

/** 把交叉编译出的 jar 收集到 dist/，并给每个 jar 单独生成 .sha256。 */
val distJarAll = tasks.register("distJarAll") {
    group = "misaka"
    description = "把交叉编译的可执行 jar（Windows / Linux / macOS）收集到项目根目录 dist/"
    dependsOn(jarDistTasks)
    outputs.upToDateWhen { false }

    doLast {
        val outDir = distDir.asFile
        outDir.mkdirs()
        jarDistTasks.forEach { jarTask ->
            val jar = jarTask.get().archiveFile.get().asFile
            jar.copyTo(File(outDir, jar.name), overwrite = true)
            // 单独一份 .sha256：发布到更新服务时是按「每个平台一个文件」登记校验值的，
            // 汇总的 SHA256SUMS.txt 在那里用不上
            File(outDir, jar.name + ".sha256").writeText("${sha256Hex(jar)}  ${jar.name}\n")
            logger.lifecycle("可执行 jar: ${jar.name}（${jar.length() / 1024 / 1024} MB）")
        }
    }
}


