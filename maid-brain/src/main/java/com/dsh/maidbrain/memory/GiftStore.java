package com.dsh.maidbrain.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 每日礼物记录：config/maid_brain/gifts.json（女仆 UUID → 上次送礼的游戏日数）。
 * 用于「每个游戏日只送一次」，跨重启不重送。
 */
public final class GiftStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("maid_brain").resolve("gifts.json");
    private static Map<String, Long> given = new HashMap<>();

    private GiftStore() {
    }

    /** 该女仆在这个游戏日是否已送过 */
    public static synchronized boolean isGiven(String uuid, long day) {
        load();
        Long last = given.get(uuid);
        return last != null && last >= day;
    }

    public static synchronized void mark(String uuid, long day) {
        load();
        given.put(uuid, day);
        save();
    }

    private static void load() {
        if (Files.exists(FILE)) {
            try {
                Map<String, Long> parsed = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8),
                        new TypeToken<HashMap<String, Long>>() {
                        }.getType());
                if (parsed != null) {
                    given = new HashMap<>(parsed);
                }
            } catch (Exception e) {
                com.dsh.maidbrain.MaidBrainMod.LOGGER.warn("gifts.json 读取失败: {}", e.toString());
            }
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(given), StandardCharsets.UTF_8);
        } catch (IOException e) {
            com.dsh.maidbrain.MaidBrainMod.LOGGER.warn("gifts.json 写入失败: {}", e.toString());
        }
    }
}
