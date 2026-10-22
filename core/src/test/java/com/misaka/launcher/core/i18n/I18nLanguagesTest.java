package com.misaka.launcher.core.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三语与外部翻译文件的契约。
 *
 * <p>锁住三件事：内置语言都在、<b>缺键回落</b>不会把键名吐到界面上、
 * 数据目录里的翻译文件既能覆盖现有语言也能新增一门语言。</p>
 */
class I18nLanguagesTest {

    @AfterEach
    void restore() {
        // 全局单例：跑完必须还原，否则后面的用例会看到法语/自定义语言
        I18n.init(null);
        I18n.setLanguage(I18n.ZH);
    }

    @Test
    void 内置语言应包含中英法() {
        assertEquals(java.util.List.of("zh_CN", "en_US", "fr_FR"), I18n.availableLanguages());
        // 语言按钮上显示的是语言自己的名字，不是标签
        assertEquals("简体中文", I18n.displayName("zh_CN"));
        assertEquals("English", I18n.displayName("en_US"));
        assertEquals("Français", I18n.displayName("fr_FR"));
    }

    @Test
    void 切换语言后应取到对应文案() {
        I18n.setLanguage("fr_FR");
        assertEquals("Accueil", I18n.t("nav.home"));
        I18n.setLanguage("en_US");
        assertEquals("Home", I18n.t("nav.home"));
        I18n.setLanguage("zh");
        assertEquals("主页", I18n.t("nav.home"));
    }

    @Test
    void 法语缺键时应回落到英语而不是显示键名() {
        // 法语包里留一个必然不存在的键，验证回落链 current → en → zh
        I18n.setLanguage("fr_FR");
        String unknown = "no.such.key.anywhere";
        assertEquals(unknown, I18n.t(unknown), "三层都缺时返回键名，便于在界面上暴露漏翻");

        // 已知键在三种语言里都不该是键名本身
        for (String tag : I18n.availableLanguages()) {
            I18n.setLanguage(tag);
            assertFalse(I18n.t("nav.home").equals("nav.home"), tag + " 缺少 nav.home");
        }
    }

    @Test
    void 外部翻译文件可覆盖文案并可新增语言(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("messages_fr_FR.properties"),
                "nav.home=Accueil (perso)\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("messages_es_ES.properties"),
                "language.name=Español\nnav.home=Inicio\n", StandardCharsets.UTF_8);

        I18n.init(dir);

        assertTrue(I18n.availableLanguages().contains("es_ES"), "外部文件应新增一门语言");
        I18n.setLanguage("fr_FR");
        assertEquals("Accueil (perso)", I18n.t("nav.home"), "外部文件应覆盖内置文案");
        assertEquals("Téléchargements", I18n.t("nav.download"), "未覆盖的键仍用内置译文");

        I18n.setLanguage("es_ES");
        assertEquals("Inicio", I18n.t("nav.home"));
        // 西语只写了两个键，其余必须回落到英语（而不是键名或空白）
        assertEquals("Welcome back", I18n.t("home.title"), "没翻的键应回落到英语");
    }
}
