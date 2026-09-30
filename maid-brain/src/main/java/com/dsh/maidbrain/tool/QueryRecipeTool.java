package com.dsh.maidbrain.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tool #4: live recipe & source query over the modpack's runtime data (JEI 式多渠道).
 * Covers vanilla + all modded + KubeJS-generated recipes.
 * Modes: make = how to craft (recipe type shows WHICH workstation/machine);
 * use = what recipes consume an item; find = list recipe ids containing a fragment;
 * drop = which MOBS drop the item (simulates every entity loot table);
 * trade = which villager / wandering-trader trades buy or sell the item.
 */
public class QueryRecipeTool implements ITool<QueryRecipeTool.Result> {
    public static final String TOOL_ID = "query_recipe";
    private static final int MAX_RECIPES = 6;

    public record Result(String item, String mode) {
        public static final Codec<Result> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("item").forGetter(Result::item),
                Codec.STRING.optionalFieldOf("mode", "make").forGetter(Result::mode)
        ).apply(i, Result::new));
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Query the modpack's LIVE data for item sources, JEI-style (vanilla + all mods + KubeJS). "
                + "mode 'make': how to craft an item — the recipe TYPE tells which workstation/machine is needed "
                + "(e.g. minecraft:smelting = 熔炉, modded ids = that mod's machine). "
                + "mode 'use': which recipes consume the item. "
                + "mode 'find': list recipe ids containing the fragment. "
                + "mode 'drop': which MOBS can drop the item (simulates entity loot tables). "
                + "mode 'trade': which villager professions / wandering trader trade it (emerald exchange). "
                + "Pass English snake_case item id fragments. Use this FIRST for 'how to get X' questions.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("item", StringParameter.create()
                .setTitle("item")
                .setDescription("item id fragment, e.g. 'iron_ingot' or 'steel_plate'"), true);
        root.addProperties("mode", StringParameter.create()
                .setTitle("mode")
                .setDescription("make | use | find | drop | trade")
                .addEnumValues("make", "use", "find", "drop", "trade"), false);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return Result.CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result arg, LLMCallback callback) {
        callback.runOnServerThread(() -> {
            var maid = callback.getMaid();
            String frag = arg.item() == null ? "" : arg.item().trim().toLowerCase();
            String mode = arg.mode() == null ? "make" : arg.mode().trim().toLowerCase();
            if (frag.isEmpty()) {
                callback.addToolResult("item 参数为空，无法查询配方", toolCallId);
                return;
            }
            var server = maid.getServer();
            if (server == null) {
                callback.addToolResult("服务器实例不可用，无法查询配方", toolCallId);
                return;
            }
            List<String> lines = new ArrayList<>();
            switch (mode) {
                case "drop" -> lines.addAll(dropSources(maid, frag));
                case "trade" -> lines.addAll(tradeSources(frag));
                default -> {
                    for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
                        if (lines.size() >= MAX_RECIPES) {
                            lines.add("…(仅显示前 " + MAX_RECIPES + " 条，可让用户细化关键词)");
                            break;
                        }
                        String rid = r.getId().toString();
                        ItemStack result = r.getResultItem(server.registryAccess());
                        String resultId = key(result);
                        String type = typeName(r);
                        switch (mode) {
                            case "use" -> {
                                boolean hit = false;
                                for (Ingredient ing : r.getIngredients()) {
                                    for (ItemStack st : ing.getItems()) {
                                        if (key(st).contains(frag)) {
                                            hit = true;
                                            break;
                                        }
                                    }
                                    if (hit) break;
                                }
                                if (hit) {
                                    lines.add("配方 [" + rid + "] → " + resultId + " x" + result.getCount()
                                            + "  含该材料的槽位见: " + ingredients(r));
                                }
                            }
                            case "find" -> {
                                if (rid.contains(frag)) {
                                    lines.add("配方: " + rid + " → " + resultId + " x" + result.getCount());
                                }
                            }
                            default -> {
                                if (resultId.contains(frag) || rid.contains(frag)) {
                                    lines.add("合成 [工坊:" + type + "] " + rid + " → " + resultId + " x" + result.getCount()
                                            + "  材料: " + ingredients(r));
                                }
                            }
                        }
                    }
                }
            }
            String out;
            if (lines.isEmpty()) {
                out = "运行时配方中未找到与「" + frag + "」相关的条目（mode=" + mode + "）。"
                        + "可尝试更准确的英文物品 id，或改用 search_pack_guide / query_minecraft_wiki。";
            } else {
                out = "运行时配方查询（关键词 " + frag + ", mode=" + mode + "）:\n" + String.join("\n", lines);
            }
            com.dsh.maidbrain.MaidBrainMod.LOGGER.info("query_recipe '{}'/{}: {} hits", frag, mode, lines.size());
            callback.addToolResult(out, toolCallId);
        });
        return callback;
    }

    /** 怪物掉落：模拟每个实体的战利品表各 roll 10 次，找能掉出目标物品的 */
    private static List<String> dropSources(EntityMaid maid, String frag) {
        List<String> out = new ArrayList<>();
        var server = maid.getServer();
        if (server == null) {
            return List.of("服务器实例不可用");
        }
        LootParams params;
        try {
            params = new LootParams.Builder((net.minecraft.server.level.ServerLevel) maid.level())
                    .withParameter(LootContextParams.ORIGIN, maid.position())
                    .withParameter(LootContextParams.THIS_ENTITY, maid)
                    .withOptionalParameter(LootContextParams.DAMAGE_SOURCE, maid.damageSources().generic())
                    .create(LootContextParamSets.ENTITY);
        } catch (Exception e) {
            return List.of("战利品参数构建失败: " + e);
        }
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation tableId = type.getDefaultLootTable();
            if (tableId == null) {
                continue;
            }
            var table = server.getLootData().getLootTable(tableId);
            Set<String> dropped = new LinkedHashSet<>();
            try {
                for (int roll = 0; roll < 10; roll++) {
                    for (ItemStack st : table.getRandomItems(params)) {
                        if (key(st).contains(frag)) {
                            dropped.add(key(st) + " x" + st.getCount());
                        }
                    }
                }
            } catch (Exception ignored) {
                continue; // 需要特殊上下文的表跳过
            }
            if (!dropped.isEmpty()) {
                out.add("击杀 " + BuiltInRegistries.ENTITY_TYPE.getKey(type) + " 可能掉落: "
                        + String.join(" | ", dropped));
                if (out.size() >= 8) {
                    out.add("…(仅显示前 8 种生物)");
                    break;
                }
            }
        }
        if (out.isEmpty()) {
            out.add("没有生物的常规掉落表能直接掉出「" + frag + "」（可能需要抢夺附魔/着火等条件，或是任务奖励/交易获得）");
        }
        out.add("注：模拟不带抢夺/着火等条件，熟食/稀有掉落可能未显示。");
        return out;
    }

    /** 村民/游商兑换：反射读取原版交易表，找买/卖目标物品的交易 */
    private static List<String> tradeSources(String frag) {
        List<String> out = new ArrayList<>();
        out.addAll(readTradeMap("VILLAGER_DEFAULT_TRADES", "村民", frag));
        out.addAll(readTradeMap("WANDERING_TRADER_TRADES", "游商", frag));
        if (out.isEmpty()) {
            out.add("原版村民/游商交易表中没有「" + frag + "」相关交易（模组自定义交易可能不在其中）");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> readTradeMap(String fieldName, String label, String frag) {
        List<String> out = new ArrayList<>();
        try {
            Class<?> cls = Class.forName("net.minecraft.world.entity.npc.VillagerTrades");
            Field field = cls.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object mapObj = field.get(null);
            if (!(mapObj instanceof Map<?, ?> trades)) {
                return out;
            }
            for (Map.Entry<?, ?> entry : trades.entrySet()) {
                String profession = String.valueOf(entry.getKey());
                Object tiers = entry.getValue();
                if (tiers == null) {
                    continue;
                }
                int n = Array.getLength(tiers);
                for (int t = 0; t < n; t++) {
                    Object tier = Array.get(tiers, t);
                    if (tier == null) {
                        continue;
                    }
                    int m = Array.getLength(tier);
                    for (int i = 0; i < m; i++) {
                        Object listing = Array.get(tier, i);
                        if (listing == null) {
                            continue;
                        }
                        List<String> parts = new ArrayList<>();
                        boolean hit = false;
                        for (Field f : listing.getClass().getDeclaredFields()) {
                            if (!f.getType().equals(ItemStack.class)) {
                                continue;
                            }
                            f.setAccessible(true);
                            Object v = f.get(listing);
                            if (v instanceof ItemStack st && !st.isEmpty()) {
                                String k = key(st);
                                if (k.contains(frag)) {
                                    hit = true;
                                }
                                parts.add(k + "x" + st.getCount());
                            }
                        }
                        if (hit && parts.size() >= 2) {
                            out.add(label + "[" + profession + "] 交易: " + String.join(" ⇄ ", parts));
                            if (out.size() >= 8) {
                                return out;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            out.add(label + "交易表读取失败: " + t);
        }
        return out;
    }

    /** 配方类型 id：提示需要哪种工作方块/模组机器（JEI 式分类） */
    private static String typeName(Recipe<?> r) {
        var key = BuiltInRegistries.RECIPE_TYPE.getKey(r.getType());
        return key == null ? "unknown" : key.toString();
    }

    private static String key(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        var rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return rl == null ? "unknown" : rl.toString();
    }

    private static String ingredients(Recipe<?> r) {
        Set<String> parts = new LinkedHashSet<>();
        for (Ingredient ing : r.getIngredients()) {
            for (ItemStack st : ing.getItems()) {
                parts.add(key(st));
                if (parts.size() >= 9) {
                    return String.join(" | ", parts);
                }
            }
        }
        if (parts.isEmpty()) {
            return "(无材料列表, 可能是熔炼/其他类型配方)";
        }
        return String.join(" | ", parts);
    }
}
