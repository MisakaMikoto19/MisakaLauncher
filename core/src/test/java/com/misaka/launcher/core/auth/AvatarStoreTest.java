package com.misaka.launcher.core.auth;

import com.misaka.launcher.core.config.PortablePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 头像本地存储验证。
 *
 * <p>离线账户的头像要跨次启动复用 —— 否则每次打开启动器都要重走
 * 「用户名 → UUID → 会话档案 → 皮肤直链」几跳网络（国内大多不可达，每跳都要等超时）。</p>
 */
class AvatarStoreTest {

    @TempDir
    Path tempDir;

    private AvatarStore store() {
        return new AvatarStore(PortablePaths.resolve(false, tempDir.toString()));
    }

    private static final String KEY = "acc-1|default|55c80999cd2e3324a4c7aa4c83b7074b";

    @Test
    void writtenEntryShouldBeReadableAndFresh() {
        AvatarStore store = store();
        byte[] png = {1, 2, 3, 4};

        store.write(KEY, png);
        Optional<AvatarStore.Cached> cached = store.read(KEY);

        assertTrue(cached.isPresent(), "写入后应能读到");
        assertArrayEquals(png, cached.get().data());
        assertTrue(cached.get().fresh(), "刚写入的应视为新鲜");
    }

    @Test
    void unknownKeyShouldMiss() {
        assertFalse(store().read(KEY).isPresent());
    }

    @Test
    void expiredEntryShouldBeReadableButStale() throws Exception {
        // 过期不删：换皮肤时界面先显示旧图，再在后台刷新，不该空白等待
        AvatarStore store = store();
        store.write(KEY, new byte[]{9, 9});

        Path file = Files.list(avatarDir()).findFirst().orElseThrow();
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 4L * 24 * 3600 * 1000));

        Optional<AvatarStore.Cached> cached = store.read(KEY);

        assertTrue(cached.isPresent(), "过期的头像仍应返回给调用方先显示");
        assertFalse(cached.get().fresh(), "过期应标记为不新鲜，以便后台刷新");
    }

    @Test
    void differentKeysOfSameAccountShouldNotOverwrite() {
        AvatarStore store = store();
        store.write("acc-1|default|uuid-a", new byte[]{1});
        store.write("acc-1|slim|uuid-a", new byte[]{2});

        assertArrayEquals(new byte[]{1}, store.read("acc-1|default|uuid-a").orElseThrow().data());
        assertArrayEquals(new byte[]{2}, store.read("acc-1|slim|uuid-a").orElseThrow().data());
    }

    @Test
    void clearingAccountShouldOnlyRemoveItsCache() {
        AvatarStore store = store();
        store.write("acc-1|default|uuid-a", new byte[]{1});
        store.write("acc-1|slim|uuid-a", new byte[]{2});
        store.write("acc-2|default|uuid-b", new byte[]{3});

        store.invalidateAccount("acc-1");

        assertFalse(store.read("acc-1|default|uuid-a").isPresent(), "acc-1 的缓存应被清理");
        assertFalse(store.read("acc-1|slim|uuid-a").isPresent());
        assertTrue(store.read("acc-2|default|uuid-b").isPresent(), "不该动到别的账户");
    }

    @Test
    void emptyKeyOrDataShouldNotCreateFiles() throws Exception {
        AvatarStore store = store();
        store.write("", new byte[]{1});
        store.write("acc-1|default|uuid", new byte[0]);
        store.write(null, new byte[]{1});

        assertFalse(Files.isDirectory(avatarDir()) && Files.list(avatarDir()).findAny().isPresent(),
                "无效应原样忽略，不产生垃圾文件");
    }

    private Path avatarDir() {
        return PortablePaths.resolve(false, tempDir.toString()).avatarsDir();
    }
}
