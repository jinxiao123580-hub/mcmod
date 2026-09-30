package com.dsh.maidbrain.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义 Tool #1：侦察报告（v2 全品类雷达）。
 * 大脑可调用：获取女仆当前位置/维度/时间/天气/生命值，
 * 以及指定半径内的敌对生物、友好生物、其他玩家——
 * 按名字分组，给出数量与最近距离。
 * 对「附近有什么」类问题，此工具比内置 nearby_entities 范围更大、信息更全。
 */
public class ScoutReportTool implements ITool<ScoutReportTool.Result> {
    public static final String TOOL_ID = "scout_report";

    public record Result(int radius) {
        public static final Codec<Result> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("radius", 32).forGetter(Result::radius)
        ).apply(i, Result::new));
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Radar scan around the maid: position, dimension, time, weather, health, plus nearby creatures grouped by "
                + "name with count and nearest distance — hostiles (zombies etc.), friendlies (animals, villagers) and other players. "
                + "Prefer this over built-in nearby_entities for any 'what is around' question; use radius 32 by default, "
                + "64 for a wide sweep.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("radius", StringParameter.create()
                .setTitle("radius")
                .setDescription("Scout radius in blocks")
                .addEnumValues("16", "32", "64"), false);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return Result.CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result arg, LLMCallback callback) {
        callback.runOnServerThread(() -> {
            EntityMaid maid = callback.getMaid();
            int r = Math.max(8, Math.min(96, arg.radius()));
            AABB box = AABB.ofSize(maid.position(), r * 2.0, Math.max(r, 24.0), r * 2.0);
            List<LivingEntity> all = maid.level().getEntitiesOfClass(LivingEntity.class, box,
                    e -> e.isAlive() && e != maid && !e.getType().toShortString().contains("armor_stand"));

            Map<String, int[]> hostile = new LinkedHashMap<>();   // name -> {count, minDist*10}
            Map<String, int[]> friendly = new LinkedHashMap<>();
            Map<String, int[]> players = new LinkedHashMap<>();
            for (LivingEntity e : all) {
                String name = e.getDisplayName().getString();
                int dist = (int) Math.round(Math.sqrt(e.distanceToSqr(maid)));
                Map<String, int[]> bucket = e instanceof Monster ? hostile
                        : (e instanceof Player ? players : friendly);
                int[] v = bucket.computeIfAbsent(name, k -> new int[]{0, Integer.MAX_VALUE});
                v[0]++;
                v[1] = Math.min(v[1], dist);
            }
            String time = maid.level().isDay() ? "白天" : (maid.level().isNight() ? "夜晚" : "黄昏");
            String weather = maid.level().isRaining() ? "下雨" : "晴朗";
            StringBuilder sb = new StringBuilder(String.format(
                    "当前位置: %s (%d, %d, %d); 时间: %s; 天气: %s; 生命: %.1f/%.1f; %d格半径扫描:",
                    maid.level().dimension().location(),
                    maid.getBlockX(), maid.getBlockY(), maid.getBlockZ(),
                    time, weather, maid.getHealth(), maid.getMaxHealth(), r));
            append(sb, "敌对生物", hostile);
            append(sb, "友好生物", friendly);
            append(sb, "玩家", players);
            if (hostile.isEmpty() && friendly.isEmpty() && players.isEmpty()) {
                sb.append(" 范围内没有任何生物");
            }
            callback.addToolResult(sb.toString(), toolCallId);
        });
        return callback;
    }

    private static void append(StringBuilder sb, String label, Map<String, int[]> groups) {
        if (groups.isEmpty()) {
            return;
        }
        List<Map.Entry<String, int[]>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort(Comparator.comparingInt(v -> v.getValue()[1]));
        sb.append(" ").append(label).append(": ");
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < 6; i++) {
            var en = sorted.get(i);
            parts.add(en.getKey() + " x" + en.getValue()[0] + "(最近" + en.getValue()[1] + "格)");
        }
        sb.append(String.join(", ", parts));
        if (sorted.size() > 6) {
            sb.append(" 等").append(sorted.size()).append("类");
        }
        sb.append(";");
    }
}
