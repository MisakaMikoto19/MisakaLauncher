package com.misaka.launcher.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 构建信息。
 *
 * <p><b>版本号的运行时唯一来源。</b>值由构建期从根目录 {@code gradle.properties} 的
 * {@code misaka.version} 生成到 classpath 上的 {@code /misaka-version.properties}
 * （见 {@code :core:generateVersionResource}），因此改版本号只需要改那一处、
 * 重新编译即可全局生效：产物文件名、界面版本、User-Agent、传给游戏的启动器版本
 * 都从同一个值派生。</p>
 *
 * <p>取值的回落顺序：</p>
 * <ol>
 *   <li>构建期生成的资源 —— 正式构建走这条；</li>
 *   <li>系统属性 {@code -Dmisaka.version=...} —— 开发运行（IDE 里没跑 Gradle 生成任务）时用；</li>
 *   <li>{@code dev} —— 都没拿到时的兜底，界面上会直接暴露出来。</li>
 * </ol>
 *
 * <p>显式拒绝含 {@code $} 的值：那是构建期模板没被展开的痕迹（与 i18n 占位符原样显示是同一类
 * 事故），宁可回落成 {@code dev} 也不能把 {@code ${version}} 印到界面上。</p>
 */
public final class BuildInfo {

    /** 构建期生成的资源，由 {@code :core:generateVersionResource} 写入。 */
    private static final String VERSION_RESOURCE = "/misaka-version.properties";

    /** 与 Gradle 属性同名，便于 IDE 运行配置里覆盖成实际版本。 */
    private static final String VERSION_PROPERTY = "misaka.version";

    /** 取不到任何版本时的兜底值：界面上直接可见，不会静默错。 */
    private static final String UNKNOWN = "dev";

    private static final String VERSION = load();

    private BuildInfo() {
    }

    /** 版本号，例如 {@code 1.0.0}。 */
    public static String version() {
        return VERSION;
    }

    private static String load() {
        String fromResource = readResource();
        if (isUsable(fromResource)) {
            return fromResource;
        }
        String fromProperty = System.getProperty(VERSION_PROPERTY);
        if (isUsable(fromProperty)) {
            return fromProperty;
        }
        return UNKNOWN;
    }

    private static String readResource() {
        try (InputStream in = BuildInfo.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in == null) {
                return null;
            }
            Properties props = new Properties();
            // 资源是 UTF-8 写的；值本身只有 ASCII，用 Reader 是为了与写入口径一致
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return props.getProperty("version");
        } catch (IOException | RuntimeException e) {
            // 资源读坏了不能让启动器起不来：退回系统属性 / dev
            return null;
        }
    }

    private static boolean isUsable(String value) {
        return value != null && !value.isBlank() && !value.contains("$");
    }
}
