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
 * 女仆任务清单存储：config/maid_brain/tasks.json
 * 条目含自增 id、文本、完成标记。用于让女仆自己维护待办，
 * 并通过 MaidTaskListContext 注入每条提示词，做到「记得自己欠什么活」。
 */
public final class TaskStore {
    public static final class Item {
        public int id;
        public String text;
        public boolean done;

        public Item() {
        }

        public Item(int id, String text, boolean done) {
            this.id = id;
            this.text = text;
            this.done = done;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("maid_brain").resolve("tasks.json");
    private static List<Item> items = new ArrayList<>();

    private TaskStore() {
    }

    public static synchronized List<Item> all() {
        load();
        return new ArrayList<>(items);
    }

    public static synchronized List<Item> pending() {
        List<Item> out = new ArrayList<>();
        for (Item i : all()) {
            if (!i.done) {
                out.add(i);
            }
        }
        return out;
    }

    public static synchronized int add(String text) {
        load();
        if (text == null || text.isBlank()) {
            return -1;
        }
        int nextId = 1;
        for (Item i : items) {
            nextId = Math.max(nextId, i.id + 1);
        }
        items.add(new Item(nextId, text.trim(), false));
        save();
        return nextId;
    }

    /** 按 "#3" / "3" / 文本片段定位，标记完成；返回命中的条目描述，未命中返回 null */
    public static synchronized String markDone(String target) {
        Item it = locate(target);
        if (it == null) {
            return null;
        }
        it.done = true;
        save();
        return "#" + it.id + " " + it.text;
    }

    public static synchronized String remove(String target) {
        Item it = locate(target);
        if (it == null) {
            return null;
        }
        items.remove(it);
        save();
        return "#" + it.id + " " + it.text;
    }

    /** 清除已完成条目，返回清除数量 */
    public static synchronized int clearDone() {
        load();
        int before = items.size();
        items.removeIf(i -> i.done);
        save();
        return before - items.size();
    }

    private static Item locate(String target) {
        load();
        if (target == null || target.isBlank()) {
            return null;
        }
        String t = target.trim();
        // 纯数字或 #数字 → 按 id
        String num = t.startsWith("#") ? t.substring(1) : t;
        try {
            int id = Integer.parseInt(num);
            for (Item i : items) {
                if (i.id == id) {
                    return i;
                }
            }
        } catch (NumberFormatException ignored) {
        }
        // 文本片段匹配
        for (Item i : items) {
            if (!i.done && i.text.contains(t)) {
                return i;
            }
        }
        for (Item i : items) {
            if (i.text.contains(t)) {
                return i;
            }
        }
        return null;
    }

    private static void load() {
        if (Files.exists(FILE)) {
            try {
                String raw = Files.readString(FILE, StandardCharsets.UTF_8);
                List<Item> parsed = GSON.fromJson(raw, new TypeToken<List<Item>>() {
                }.getType());
                if (parsed != null) {
                    items = new ArrayList<>(parsed);
                }
            } catch (Exception e) {
                com.dsh.maidbrain.MaidBrainMod.LOGGER.warn("tasks.json 读取失败: {}", e.toString());
            }
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(items), StandardCharsets.UTF_8);
        } catch (IOException e) {
            com.dsh.maidbrain.MaidBrainMod.LOGGER.warn("tasks.json 写入失败: {}", e.toString());
        }
    }
}
