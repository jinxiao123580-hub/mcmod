package com.dsh.maidbrain.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 女仆地标存储：config/maid_brain/waypoints.json
 * 记录「名字 → 维度+坐标」，让主人可以说“记住这里叫家”“回家”“去矿洞”。
 * 供 WaypointTool 使用；可由玩家手改文件补充地标。
 */
public final class WaypointStore {
    public static final class Wp {
        public String name;
        public String dim;
        public int x;
        public int y;
        public int z;
        public long ts;

        public Wp() {
        }

        public Wp(String name, String dim, int x, int y, int z, long ts) {
            this.name = name;
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ts = ts;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("maid_brain").resolve("waypoints.json");
    private static Map<String, Wp> points = new LinkedHashMap<>();

    private WaypointStore() {
    }

    public static synchronized List<Wp> all() {
        load();
        return new ArrayList<>(points.values());
    }

    /** 同名覆盖，返回是否为新地点 */
    public static synchronized boolean save(String name, String dim, int x, int y, int z) {
        load();
        String key = norm(name);
        if (key.isEmpty()) {
            return false;
        }
        boolean isNew = !points.containsKey(key);
        points.put(key, new Wp(name.trim(), dim, x, y, z, System.currentTimeMillis()));
        save();
        return isNew;
    }

    public static synchronized Wp find(String name) {
        load();
        String key = norm(name);
        if (key.isEmpty()) {
            return null;
        }
        Wp exact = points.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Wp> e : points.entrySet()) {
            if (e.getKey().contains(key) || key.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    public static synchronized Wp remove(String name) {
        load();
        Wp wp = find(name);
        if (wp != null) {
            points.remove(norm(wp.name));
            save();
        }
        return wp;
    }

    private static String norm(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    private static void load() {
        if (Files.exists(FILE)) {
            try {
                String raw = Files.readString(FILE, StandardCharsets.UTF_8);
                Map<String, Wp> parsed = GSON.fromJson(raw, new TypeToken<LinkedHashMap<String, Wp>>() {
                }.getType());
                if (parsed != null) {
                    points = new LinkedHashMap<>(parsed);
                }
            } catch (Exception e) {
                com.dsh.maidbrain.MaidBrainMod.LOGGER.warn("waypoints.json 读取失败: {}", e.toString());
            }
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(points), StandardCharsets.UTF_8);
        } catch (IOException e) {
            com.dsh.maidbrain.MaidBrainMod.LOGGER.warn("waypoints.json 写入失败: {}", e.toString());
        }
    }
}
