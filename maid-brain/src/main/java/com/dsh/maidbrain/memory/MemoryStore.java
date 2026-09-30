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
import java.util.List;

/**
 * 女仆长期记忆存储：config/maid_brain/memory.json
 * 结构为字符串数组，最新在末尾；上限 100 条（超出时淘汰最旧）。
 * 复用 TLM 官方 GameContextRegister 机制：注册为 prompt 类别后，
 * 每条 AI 请求的 <context> 都会自动带上记忆摘要（见 MemoryContext）。
 */
public final class MemoryStore {
    private static final int MAX = 100;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("maid_brain").resolve("memory.json");
    private static List<String> facts = new ArrayList<>();

    private MemoryStore() {
    }

    public static synchronized List<String> all() {
        load();
        return new ArrayList<>(facts);
    }

    public static synchronized void add(String fact) {
        load();
        if (fact == null || fact.isBlank()) {
            return;
        }
        facts.add(fact.trim());
        while (facts.size() > MAX) {
            facts.remove(0);
        }
        save();
    }

    /** 删除包含片段的条目，返回删除数 */
    public static synchronized int forget(String fragment) {
        load();
        String needle = fragment == null ? "" : fragment.trim();
        if (needle.isEmpty()) {
            return 0;
        }
        int before = facts.size();
        facts.removeIf(f -> f.contains(needle));
        save();
        return before - facts.size();
    }

    public static synchronized List<String> find(String query) {
        String needle = query == null ? "" : query.trim();
        if (needle.isEmpty()) {
            return all();
        }
        List<String> hits = new ArrayList<>();
        for (String f : all()) {
            if (f.contains(needle)) {
                hits.add(f);
            }
        }
        return hits;
    }

    private static void load() {
        if (Files.exists(FILE)) {
            try {
                String raw = Files.readString(FILE, StandardCharsets.UTF_8);
                List<String> parsed = GSON.fromJson(raw, new TypeToken<List<String>>() {
                }.getType());
                if (parsed != null) {
                    facts = new ArrayList<>(parsed);
                }
            } catch (Exception e) {
                com.dsh.maidbrain.MaidBrainMod.LOGGER.warn("memory.json 读取失败, 使用空记忆: {}", e.toString());
            }
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(facts), StandardCharsets.UTF_8);
        } catch (IOException e) {
            com.dsh.maidbrain.MaidBrainMod.LOGGER.warn("memory.json 写入失败: {}", e.toString());
        }
    }
}
