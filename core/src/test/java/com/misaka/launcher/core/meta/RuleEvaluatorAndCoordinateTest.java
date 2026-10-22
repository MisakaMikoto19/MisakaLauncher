package com.misaka.launcher.core.meta;

import com.misaka.launcher.core.model.MavenCoordinate;
import com.misaka.launcher.core.model.VersionDetail;
import com.misaka.launcher.core.util.ArchUtils;
import com.misaka.launcher.core.util.OsUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台规则求值与 Maven 坐标解析验证。
 */
class RuleEvaluatorAndCoordinateTest {

    // ------------------------------------------------------------------
    // Maven 坐标
    // ------------------------------------------------------------------

    @Test
    void shortCoordinateShouldYieldRepoPath() {
        MavenCoordinate coordinate = MavenCoordinate.parse("org.ow2.asm:asm:9.5");
        assertEquals("org/ow2/asm/asm/9.5/asm-9.5.jar", coordinate.toPath());
        assertEquals("asm-9.5.jar", coordinate.fileName());
    }

    @Test
    void longCoordinateShouldDetectClassifier() {
        MavenCoordinate coordinate = MavenCoordinate.parse(
                "net.minecraftforge:forge:1.20.1-47.2.0:universal");
        assertEquals("net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar",
                coordinate.toPath());
        assertEquals("universal", coordinate.classifier());
    }

    @Test
    void extensionSuffixShouldBeParsed() {
        MavenCoordinate coordinate = MavenCoordinate.parse("com.example:artifact:1.0@zip");
        assertEquals("com/example/artifact/1.0/artifact-1.0.zip", coordinate.toPath());

        // 第四段为已知扩展名时视为扩展名而非分类器
        MavenCoordinate withExt = MavenCoordinate.parse("com.example:artifact:1.0:zip");
        assertEquals("com/example/artifact/1.0/artifact-1.0.zip", withExt.toPath());
    }

    @Test
    void invalidCoordinateShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> MavenCoordinate.parse("only:two"));
        assertThrows(IllegalArgumentException.class, () -> MavenCoordinate.parse(""));
        assertThrows(IllegalArgumentException.class, () -> MavenCoordinate.parse(null));
    }

    // ------------------------------------------------------------------
    // 规则求值
    // ------------------------------------------------------------------

    private VersionDetail.Rule allow(String osName) {
        VersionDetail.Rule rule = new VersionDetail.Rule();
        rule.action = "allow";
        rule.os = new VersionDetail.OsRule();
        rule.os.name = osName;
        return rule;
    }

    private VersionDetail.Rule disallow(String osName) {
        VersionDetail.Rule rule = new VersionDetail.Rule();
        rule.action = "disallow";
        rule.os = new VersionDetail.OsRule();
        rule.os.name = osName;
        return rule;
    }

    @Test
    void rulesAbsentShouldAllow() {
        assertTrue(RuleEvaluator.isAllowed(null, Map.of()));
        assertTrue(RuleEvaluator.isAllowed(List.of(), Map.of()));
    }

    @Test
    void onlyCurrentPlatformShouldBeAllowed() {
        assertTrue(RuleEvaluator.isAllowed(List.of(allow(OsUtils.mojangOsName())), Map.of()));
        assertFalse(RuleEvaluator.isAllowed(List.of(allow("a-made-up-os")), Map.of()));
    }

    @Test
    void laterRuleShouldOverrideEarlier() {
        // 先允许所有平台，再排除当前平台 -> 最终不允许
        List<VersionDetail.Rule> rules = List.of(allow(OsUtils.mojangOsName()), disallow(OsUtils.mojangOsName()));
        assertFalse(RuleEvaluator.isAllowed(rules, Map.of()));

        // 先排除再允许 -> 最终允许
        List<VersionDetail.Rule> reversed = List.of(disallow(OsUtils.mojangOsName()), allow(OsUtils.mojangOsName()));
        assertTrue(RuleEvaluator.isAllowed(reversed, Map.of()));
    }

    @Test
    void featureFlagShouldParticipate() {
        VersionDetail.Rule rule = new VersionDetail.Rule();
        rule.action = "allow";
        rule.features = Map.of("has_custom_resolution", true);

        assertFalse(RuleEvaluator.isAllowed(List.of(rule), RuleEvaluator.defaultFeatures()),
                "默认未开启自定义分辨率时应不匹配");
        assertTrue(RuleEvaluator.isAllowed(List.of(rule), Map.of("has_custom_resolution", true)),
                "开启后应匹配");
    }

    @Test
    void archConditionShouldCompareCanonically() {
        VersionDetail.Rule rule = new VersionDetail.Rule();
        rule.action = "allow";
        rule.os = new VersionDetail.OsRule();
        rule.os.name = OsUtils.mojangOsName();
        rule.os.arch = ArchUtils.mojangArch();

        assertTrue(RuleEvaluator.isAllowed(List.of(rule), Map.of()));
    }

    @Test
    void badVersionRegexShouldNotFailEvaluation() {
        VersionDetail.Rule rule = new VersionDetail.Rule();
        rule.action = "allow";
        rule.os = new VersionDetail.OsRule();
        rule.os.version = "([unclosed";

        // 模式非法时该条规则不命中，且不抛异常
        assertFalse(RuleEvaluator.isAllowed(List.of(rule), Map.of()));
    }

    @Test
    void versionRegexShouldUsePartialMatch() {
        VersionDetail.Rule rule = new VersionDetail.Rule();
        rule.action = "allow";
        rule.os = new VersionDetail.OsRule();
        // 官方元数据中的写法形如 ^10\. ，需要 find 语义才能命中 10.0
        rule.os.version = "^" + java.util.regex.Pattern.quote(System.getProperty("os.version", "x"));

        assertTrue(RuleEvaluator.isAllowed(List.of(rule), Map.of()),
                "当前系统版本应命中自身版本正则");
    }

    @Test
    void defaultFeatureFlagsShouldCoverKnownKeys() {
        Map<String, Boolean> features = RuleEvaluator.defaultFeatures();
        for (String key : List.of("is_demo_user", "has_custom_resolution", "has_quick_plays_support",
                "is_quick_play_singleplayer", "is_quick_play_multiplayer", "is_quick_play_realms")) {
            assertTrue(features.containsKey(key), "缺少特性开关: " + key);
            assertEquals(Boolean.FALSE, features.get(key));
        }
    }
}
