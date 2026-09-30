package com.dsh.maidbrain.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 好感度存储：config/maid_brain/relation.json
 * 以玩家 UUID 为键记录：好感分（0-100）、上次交谈时间、完成家务数、收到礼物数。
 * 好感度会注入每条提示词（MaidRelationContext），也可被决策层读取——
 * 低好感时她会闹脾气、拒绝干活，这是现成好感模组做不到的。
 */
public final class RelationStore {
    public static final class Rel {
        public int score = 60;
        public long lastTalk = 0L;
        public int tasksDone = 0;
        public int gifts = 0;

        public Rel() {
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("maid_brain").resolve("relation.json");
    private static Map<String, Rel> rels = new LinkedHashMap<>();

    private RelationStore() {
    }

    public static synchronized Rel of(String key) {
        load();
        return rels.computeIfAbsent(key == null || key.isBlank() ? "unknown" : key, k -> new Rel());
    }

    public static synchronized int score(String key) {
        return of(key).score;
    }

    /** 调整好感，返回新分数 */
    public static synchronized int add(String key, int delta) {
        Rel r = of(key);
        r.score = Math.max(0, Math.min(100, r.score + delta));
        save();
        return r.score;
    }

    /** 交谈：更新时间戳并小幅加成（由 ChatBarBridge 调用） */
    public static synchronized void touchTalk(String key) {
        Rel r = of(key);
        r.lastTalk = System.currentTimeMillis();
        save();
    }

    /** 完成一件家务 */
    public static synchronized void countTask(String key, int bonus) {
        Rel r = of(key);
        r.tasksDone++;
        r.score = Math.max(0, Math.min(100, r.score + bonus));
        save();
    }

    public static synchronized void countGift(String key, int bonus) {
        Rel r = of(key);
        r.gifts++;
        r.score = Math.max(0, Math.min(100, r.score + bonus));
        save();
    }

    /** 中文档位，供提示词与口头汇报使用 */
    public static String level(int score) {
        if (score <= 20) {
            return "冷淡（会闹脾气，可能拒绝干活）";
        }
        if (score <= 40) {
            return "生分";
        }
        if (score <= 60) {
            return "普通";
        }
        if (score <= 80) {
            return "亲近";
        }
        return "挚爱";
    }

    public static String lastTalkText(long lastTalk) {
        if (lastTalk <= 0) {
            return "还没聊过";
        }
        long minutes = Math.max(0, (System.currentTimeMillis() - lastTalk) / 60000L);
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        return (minutes / 60) + " 小时前";
    }

    private static void load() {
        if (Files.exists(FILE)) {
            try {
                String raw = Files.readString(FILE, StandardCharsets.UTF_8);
                Map<String, Rel> parsed = GSON.fromJson(raw, new TypeToken<LinkedHashMap<String, Rel>>() {
                }.getType());
                if (parsed != null) {
                    rels = new LinkedHashMap<>(parsed);
                }
            } catch (Exception e) {
                com.dsh.maidbrain.MaidBrainMod.LOGGER.warn("relation.json 读取失败: {}", e.toString());
            }
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(rels), StandardCharsets.UTF_8);
        } catch (IOException e) {
            com.dsh.maidbrain.MaidBrainMod.LOGGER.warn("relation.json 写入失败: {}", e.toString());
        }
    }
}
