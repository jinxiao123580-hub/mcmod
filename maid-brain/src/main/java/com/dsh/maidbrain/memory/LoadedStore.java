package com.dsh.maidbrain.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「常加载」女仆名单：config/maid_brain/loaded.json（女仆 UUID 集合）。
 * 配合 MaidChunkLoader：名单里的女仆所在区块会被强制加载（原版 forceload 机制，跨重启持久），
 * 人不在附近她也继续.tick、继续干活。
 */
public final class LoadedStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("maid_brain").resolve("loaded.json");
    private static Set<String> ids = new LinkedHashSet<>();

    private LoadedStore() {
    }

    public static synchronized boolean contains(String uuid) {
        load();
        return ids.contains(uuid);
    }

    public static synchronized void add(String uuid) {
        load();
        ids.add(uuid);
        save();
    }

    public static synchronized void remove(String uuid) {
        load();
        ids.remove(uuid);
        save();
    }

    private static void load() {
        if (Files.exists(FILE)) {
            try {
                Set<String> parsed = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8),
                        new TypeToken<LinkedHashSet<String>>() {
                        }.getType());
                if (parsed != null) {
                    ids = new LinkedHashSet<>(parsed);
                }
            } catch (Exception e) {
                com.dsh.maidbrain.MaidBrainMod.LOGGER.warn("loaded.json 读取失败: {}", e.toString());
            }
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(ids), StandardCharsets.UTF_8);
        } catch (IOException e) {
            com.dsh.maidbrain.MaidBrainMod.LOGGER.warn("loaded.json 写入失败: {}", e.toString());
        }
    }
}
