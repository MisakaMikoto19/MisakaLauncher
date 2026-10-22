package com.misaka.launcher.core.log;

import com.misaka.launcher.core.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 崩溃诊断规则验证。
 *
 * <p>样本取自真实崩溃报告里的关键行（例如拼接图集失败时的
 * {@code StitcherException: Unable to fit}），而不是臆造的错误文本 ——
 * 规则表的价值恰恰在于命中真实故障，用编造的字符串测试等于没测。</p>
 */
class CrashAdvisorTest {

    @TempDir
    Path tempDir;

    @Test
    void atlasOverflowShouldBeTextureIssueNotGpuAdvice() {
        String report = "---- Minecraft Crash Report ----\n"
                + "// Embeddium instance tainted by mods: [oculus, embeddium_extra]\n"
                + "Time: 2026-10-09 20:13:05\n"
                + "Description: Stitching\n\n"
                + "net.minecraft.client.renderer.texture.StitcherException: Unable to fit: "
                + "dragonrise_reforge:item/cnfast - size: 512x512 "
                + "- Maybe try a lower resolution resourcepack?\n"
                + "\tat net.minecraft.client.renderer.texture.Stitcher.m_118193_(Stitcher.java:67)\n"
                + "Details:\n"
                + "\tSprites: mtr:vehicle/a320[4096x4096],some:block[512x512]\n"
                + "\tMax Texture Size: 16384\n";

        CrashAdvisor.Diagnosis diagnosis = CrashAdvisor.analyze(report);

        // 真实报告里 "Max Texture Size: 16384" 与拼接失败总是同时出现，
        // 它是「图集边长上限」这一诊断信息，不能被当成显存不足的证据
        assertEquals(1, diagnosis.advice().size(), "不该连坐出 gpuMemory");
        assertEquals("atlasFull", diagnosis.advice().get(0).id());
        // 关键回归点：真实案例（RX 7900 GRE、驱动正常、独显已在用）被旧规则判成「显存不足」，
        // 并据此弹「改用独显」按钮 —— 那会把用户引到完全错误的方向
        assertFalse(diagnosis.graphicsRelated(), "图集装不下与显存无关，不该提供独显相关按钮");
    }

    @Test
    void realVramFailureShouldStayGpuRelated() {
        CrashAdvisor.Diagnosis diagnosis = CrashAdvisor.analyze(
                "org.lwjgl.opengl.OpenGLException: Out of memory\n"
                        + "java.lang.OutOfMemoryError: Out of memory (direct buffer)");

        assertTrue(diagnosis.graphicsRelated());
        assertEquals(1, diagnosis.advice().size());
        assertEquals("gpuMemory", diagnosis.advice().get(0).id());
    }

    @Test
    void Java堆不足不应被误判为显存不足() {
        CrashAdvisor.Diagnosis diagnosis =
                CrashAdvisor.analyze("java.lang.OutOfMemoryError: Java heap space");

        assertFalse(diagnosis.graphicsRelated(), "堆不足与显存无关，不该给出独显建议");
        assertEquals(1, diagnosis.advice().size());
        assertEquals("javaHeap", diagnosis.advice().get(0).id());
    }

    @Test
    void missingDependencyShouldSuggestModConflict() {
        CrashAdvisor.Diagnosis diagnosis = CrashAdvisor.analyze(
                "net.minecraftforge.fml.ModResolutionException: "
                        + "Missing or unsupported mandatory dependencies");

        assertEquals("modConflict", diagnosis.advice().get(0).id());
    }

    @Test
    void driverCrashShouldSuggestDriverAndCrashTips() {
        CrashAdvisor.Diagnosis diagnosis = CrashAdvisor.analyze(
                "EXCEPTION_ACCESS_VIOLATION (0xc0000005)\n"
                        + "Problematic frame:\nC  [nvoglv64.dll+0x1a2b3c4]");

        assertTrue(diagnosis.graphicsRelated());
        assertEquals(2, diagnosis.advice().size());
        assertEquals("gpuDriver", diagnosis.advice().get(0).id());
        assertEquals("accessViolation", diagnosis.advice().get(1).id());
    }

    @Test
    void unknownCauseShouldReturnUnknownCategory() {
        CrashAdvisor.Diagnosis diagnosis = CrashAdvisor.analyze("Stopping!");

        assertEquals(1, diagnosis.advice().size());
        assertEquals("unknown", diagnosis.advice().get(0).id());
        assertFalse(diagnosis.graphicsRelated());
    }

    @Test
    void adviceShouldBeCapped() {
        CrashAdvisor.Diagnosis diagnosis = CrashAdvisor.analyze(
                "Unable to fit\n"
                        + "java heap space\n"
                        + "MixinApplyError\n"
                        + "UnsupportedClassVersionError\n"
                        + "nvoglv64.dll\n"
                        + "EXCEPTION_ACCESS_VIOLATION");

        assertEquals(4, diagnosis.advice().size(), "一次最多给 4 条");
    }

    /**
     * 真实崩溃报告可以有几百 KB，而原因就在前几十行。
     *
     * <p>这个用例同时锁住两件事：诊断必须能从头部拿到原因，以及
     * 「只读尾部」确实会错过它 —— 后者正是崩溃报告不能按日志尾部读取的原因。</p>
     */
    @Test
    void hugeReportShouldBeAnalyzedFromHead() throws Exception {
        Path report = tempDir.resolve("crash-report.txt");
        String content = "Description: Stitching\n\n"
                + "StitcherException: Unable to fit: some:block - size: 512x512\n"
                + "-- System Details --\n"
                + "Loaded mod list:\n"
                + "filler\n".repeat(120_000);
        Files.writeString(report, content);

        assertTrue(Files.size(report) > 512 * 1024, "样本需要足够大才有区分度");

        String head = FileUtils.readHeadLines(report, 256 * 1024, 400);
        assertTrue(head.contains("Unable to fit"), "头部读取必须包含崩溃原因");
        assertTrue(head.length() < 64 * 1024, "头部读取应有界，不能把整份报告读进来");
        assertEquals("atlasFull", CrashAdvisor.analyze(head).advice().get(0).id());

        String tail = FileUtils.readTailLines(report, 4 * 1024, 50);
        assertFalse(tail.contains("Unable to fit"), "原因在头部，只读尾部会错过它");
    }
}
