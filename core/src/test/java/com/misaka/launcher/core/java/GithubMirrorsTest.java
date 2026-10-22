package com.misaka.launcher.core.java;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitHub 加速镜像的候选展开规则。
 */
class GithubMirrorsTest {

    private static final String ASSET =
            "https://github.com/dragonwell-project/dragonwell17/releases/download/tag/file.zip";

    @Test
    void officialHashShouldExpandMirrorsWithOfficialLast() {
        List<String> urls = GithubMirrors.expand(ASSET, true);

        assertTrue(urls.size() > 1, "应包含加速镜像候选：" + urls);
        assertEquals(ASSET, urls.get(urls.size() - 1), "官方地址必须在最后兜底");
        for (String url : urls.subList(0, urls.size() - 1)) {
            assertTrue(url.endsWith(ASSET), "加速地址应是「镜像前缀 + 原始地址」：" + url);
            assertTrue(url.length() > ASSET.length(), "加速地址必须带上镜像前缀：" + url);
        }
        assertEquals(urls.size(), urls.stream().distinct().count(), "候选不应重复");
    }

    @Test
    void missingHashShouldDisableMirrors() {
        // 无法校验就无法排除投毒：宁可慢，也必须保证装到的是官方产物
        assertEquals(List.of(ASSET), GithubMirrors.expand(ASSET, false));
    }

    @Test
    void nonGithubAndBlankShouldPassThrough() {
        String tuna = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/file.zip";
        assertEquals(List.of(tuna), GithubMirrors.expand(tuna, true),
                "清华镜像本身已是国内地址，不需要再套一层加速");
        assertTrue(GithubMirrors.expand(null, true).isEmpty());
        assertTrue(GithubMirrors.expand("", true).isEmpty());
    }

    @Test
    void onlySiteApexShouldMatch() {
        assertTrue(GithubMirrors.isGithubUrl(ASSET));
        // raw / api 子域由各自的加速方案处理，套这里的前缀只会拼出无效地址
        assertFalse(GithubMirrors.isGithubUrl("https://raw.githubusercontent.com/a/b"));
        assertFalse(GithubMirrors.isGithubUrl("https://api.github.com/repos/a/b"));
        assertFalse(GithubMirrors.isGithubUrl(null));
    }
}
