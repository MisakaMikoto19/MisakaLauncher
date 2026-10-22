package com.misaka.launcher.core.model;

import java.util.Locale;

/**
 * Maven 坐标解析：{@code group:artifact:version[:classifier][@extension]}。
 * <p>Minecraft 元数据中的 {@code libraries[].name} 即该格式。官方元数据通常同时提供
 * {@code downloads.artifact.path}，此时以它为准；但 Forge 等第三方元数据常常只给出
 * 坐标与仓库地址，必须自行推导路径，故本类不可省略。</p>
 */
public final class MavenCoordinate {

    private static final java.util.Set<String> KNOWN_EXTENSIONS =
            java.util.Set.of("jar", "zip", "lzma", "txt", "json", "pom");

    private final String group;
    private final String artifact;
    private final String version;
    private final String classifier;
    private final String extension;

    private MavenCoordinate(String group, String artifact, String version, String classifier, String extension) {
        this.group = group;
        this.artifact = artifact;
        this.version = version;
        this.classifier = classifier;
        this.extension = extension;
    }

    /**
     * 解析坐标。
     *
     * @throws IllegalArgumentException 坐标格式非法
     */
    public static MavenCoordinate parse(String notation) {
        if (notation == null || notation.isBlank()) {
            throw new IllegalArgumentException("Maven 坐标为空");
        }
        String value = notation.trim();
        String extension = "jar";
        int at = value.indexOf('@');
        if (at >= 0) {
            extension = value.substring(at + 1).toLowerCase(Locale.ROOT);
            value = value.substring(0, at);
        }

        String[] parts = value.split(":");
        if (parts.length == 3) {
            return new MavenCoordinate(parts[0], parts[1], parts[2], null, extension);
        }
        if (parts.length == 4) {
            // 四段式存在两种约定：第 4 段是 classifier，或（少见）是扩展名
            String fourth = parts[3];
            if (KNOWN_EXTENSIONS.contains(fourth.toLowerCase(Locale.ROOT))) {
                return new MavenCoordinate(parts[0], parts[1], parts[2], null, fourth.toLowerCase(Locale.ROOT));
            }
            return new MavenCoordinate(parts[0], parts[1], parts[2], fourth, extension);
        }
        if (parts.length == 5) {
            return new MavenCoordinate(parts[0], parts[1], parts[2], parts[3], parts[4].toLowerCase(Locale.ROOT));
        }
        throw new IllegalArgumentException("无法解析的 Maven 坐标: " + notation);
    }

    /** 组名的目录形式，例如 {@code com.google.code.gson -> com/google/code/gson}。 */
    public String groupPath() {
        return group.replace('.', '/');
    }

    /** 文件名，例如 {@code asm-9.5.jar} 或 {@code forge-1.20.1-47.2.0-universal.jar}。 */
    public String fileName() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(artifact).append('-').append(version);
        if (classifier != null && !classifier.isBlank()) {
            sb.append('-').append(classifier);
        }
        sb.append('.').append(extension);
        return sb.toString();
    }

    /** 相对于 maven 仓库根目录的路径。 */
    public String toPath() {
        return groupPath() + "/" + artifact + "/" + version + "/" + fileName();
    }

    /** 部分 Forge 库会把版本号直接嵌入 artifact（例如 {@code net.minecraftforge:forge:1.20.1-47.2.0}），
     * 其文件名遵循 artifact-version 规则，由 {@link #fileName()} 统一处理。 */
    public String group() {
        return group;
    }

    public String artifact() {
        return artifact;
    }

    public String version() {
        return version;
    }

    public String classifier() {
        return classifier;
    }

    public String extension() {
        return extension;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(group).append(':').append(artifact).append(':').append(version);
        if (classifier != null && !classifier.isBlank()) {
            sb.append(':').append(classifier);
        }
        if (!"jar".equals(extension)) {
            sb.append('@').append(extension);
        }
        return sb.toString();
    }
}
