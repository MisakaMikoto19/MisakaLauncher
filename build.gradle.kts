plugins {
    java
}

group = "com.misaka.launcher"
version = providers.gradleProperty("misaka.version").get()

allprojects {
    group = rootProject.group
    version = rootProject.version
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(17)
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("file.encoding", "UTF-8")
        // 供 BuildInfoTest 断言「运行期读到的版本号 == gradle.properties 里的值」。
        // 刻意用另一个属性名：misaka.version 是 BuildInfo 自己的覆盖入口，不能占用。
        systemProperty("misaka.expectedVersion", version.toString())
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}

/**
 * 一键构建：编译 core + app，产出三平台分发包与单文件发布包，并做一次启动自检。
 * 真正的 jlink/jpackage 与自解压逻辑位于 :app 模块，此处只做统一入口。
 */
tasks.register("buildAll") {
    group = "misaka"
    description = "编译全部模块、跑测试、生成自带运行时的分发包与单文件发布包（等价于 build_all 脚本的 Gradle 入口）"
    dependsOn(":core:build", ":app:misakaDist", ":app:packageSingleFile", ":app:smokeTest")
}
