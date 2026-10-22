package com.misaka.launcher.core.launch;

import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.meta.VersionManifestService;
import com.misaka.launcher.core.model.VersionDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 版本继承链解析与合并。
 * <p>规则（与官方启动器一致）：</p>
 * <ul>
 *   <li>{@code libraries}：基础版在前、子版本在后，顺序影响类加载优先级；</li>
 *   <li>{@code arguments}：同理，基础版参数在前、子版本在后；</li>
 *   <li>标量字段（{@code mainClass} / {@code assets} / {@code assetIndex} / {@code downloads} /
 *       {@code javaVersion} / {@code logging}）：子版本优先，缺失时继承基础版；</li>
 *   <li>客户端主 jar：沿继承链找到第一个真实存在的 jar
 *       —— Fabric 等 profile 目录内并没有 jar，需要复用原版的。</li>
 * </ul>
 */
public final class VersionResolver {

    private static final Logger log = LoggerFactory.getLogger(VersionResolver.class);

    /** 继承链深度上限，防御元数据出现环状引用。 */
    private static final int MAX_DEPTH = 16;

    private VersionResolver() {
    }

    /**
     * 解析并合并指定版本的继承链。
     *
     * @throws IOException 版本未安装，或继承链中缺少某个版本
     */
    public static ResolvedVersion resolve(GamePaths paths, String versionId) throws IOException {
        List<VersionDetail> chain = new ArrayList<>();
        String cursor = versionId;
        int depth = 0;
        while (cursor != null && !cursor.isBlank()) {
            if (depth++ >= MAX_DEPTH) {
                throw new IOException("版本继承链过深（可能存在循环引用）: " + versionId);
            }
            VersionDetail layer = read(paths, cursor);
            chain.add(layer);
            cursor = layer.inheritsFrom;
        }

        List<String> ids = new ArrayList<>(chain.size());
        for (VersionDetail layer : chain) {
            ids.add(layer.effectiveId());
        }

        VersionDetail merged = merge(chain);
        Path clientJar = findClientJar(paths, ids);
        if (clientJar == null) {
            log.warn("版本 {} 的继承链中未找到任何客户端主 jar", versionId);
        }
        return new ResolvedVersion(versionId, merged, ids, clientJar);
    }

    /** 只读取单个版本 JSON（不做继承合并）。 */
    public static VersionDetail read(GamePaths paths, String versionId) throws IOException {
        Path json = paths.versionJson(versionId);
        if (!Files.isRegularFile(json)) {
            throw new IOException("版本未安装或缺少描述文件: " + json);
        }
        VersionDetail detail = VersionManifestService.readLocal(json);
        if (detail.id == null || detail.id.isBlank()) {
            detail.id = versionId;
        }
        return detail;
    }

    /** 判断版本是否已安装。 */
    public static boolean isInstalled(GamePaths paths, String versionId) {
        return versionId != null && !versionId.isBlank()
                && Files.isRegularFile(paths.versionJson(versionId));
    }

    /**
     * 合并继承链（入参顺序：自身 → 基础版）。
     */
    private static VersionDetail merge(List<VersionDetail> chain) {
        VersionDetail merged = new VersionDetail();
        merged.id = chain.get(0).effectiveId();
        merged.arguments = new VersionDetail.Arguments();
        merged.libraries = new ArrayList<>();
        merged.downloads = new LinkedHashMap<>();

        // 自基础版向自身叠加：后写入者覆盖前者
        for (int i = chain.size() - 1; i >= 0; i--) {
            VersionDetail layer = chain.get(i);

            if (isNotBlank(layer.mainClass)) {
                merged.mainClass = layer.mainClass;
            }
            if (isNotBlank(layer.minecraftArguments)) {
                merged.minecraftArguments = layer.minecraftArguments;
            }
            if (isNotBlank(layer.type)) {
                merged.type = layer.type;
            }
            if (isNotBlank(layer.releaseTime)) {
                merged.releaseTime = layer.releaseTime;
            }
            if (isNotBlank(layer.time)) {
                merged.time = layer.time;
            }
            if (isNotBlank(layer.assets)) {
                merged.assets = layer.assets;
            }
            if (layer.assetIndex != null) {
                merged.assetIndex = layer.assetIndex;
            }
            if (layer.downloads != null && !layer.downloads.isEmpty()) {
                merged.downloads.putAll(layer.downloads);
            }
            if (layer.javaVersion != null && layer.javaVersion.majorVersion > 0) {
                merged.javaVersion = layer.javaVersion;
            }
            if (layer.logging != null) {
                merged.logging = layer.logging;
            }
            if (layer.complianceLevel > merged.complianceLevel) {
                merged.complianceLevel = layer.complianceLevel;
            }
            if (layer.minimumLauncherVersion != null) {
                merged.minimumLauncherVersion = layer.minimumLauncherVersion;
            }

            // 列表类字段：基础版在前，子版本追加在后
            if (layer.libraries != null) {
                merged.libraries.addAll(layer.libraries);
            }
            if (layer.arguments != null) {
                if (layer.arguments.game != null) {
                    merged.arguments.game.addAll(layer.arguments.game);
                }
                if (layer.arguments.jvm != null) {
                    merged.arguments.jvm.addAll(layer.arguments.jvm);
                }
            }
        }

        // 自身 id 必须保留，否则启动参数中的 ${version_name} 会指向基础版
        merged.id = chain.get(0).effectiveId();
        merged.inheritsFrom = null;
        return merged;
    }

    /**
     * 沿继承链查找客户端主 jar。
     * <p>Forge / NeoForge 的安装器会在自己的版本目录里产出打过补丁的 jar，
     * 因此优先取自身目录；Fabric / Quilt 则退到基础版目录。</p>
     */
    private static Path findClientJar(GamePaths paths, List<String> chainIds) {
        for (String id : chainIds) {
            Path jar = paths.versionJar(id);
            if (Files.isRegularFile(jar)) {
                return jar;
            }
        }
        return null;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 供界面展示：继承链摘要，例如 {@code 1.20.1-forge-47.2.0 → 1.20.1}。 */
    public static String describeChain(ResolvedVersion resolved) {
        return String.join(" → ", resolved.inheritanceChain());
    }

    /** 便捷方法：返回继承链映射，便于排查。 */
    public static Map<String, String> debugInfo(ResolvedVersion resolved) {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("versionId", resolved.versionId());
        info.put("chain", describeChain(resolved));
        info.put("mainClass", resolved.detail().mainClass);
        info.put("clientJar", resolved.clientJar() == null ? "<缺失>" : resolved.clientJar().toString());
        info.put("libraries", String.valueOf(resolved.detail().libraries.size()));
        return info;
    }
}
