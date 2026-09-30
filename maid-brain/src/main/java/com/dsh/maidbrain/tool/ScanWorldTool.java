package com.dsh.maidbrain.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec2;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Tool #5: world scan — structures, biomes, blocks and ores.
 * structure/biome 模式：用原版 /locate 并捕获输出返回坐标；
 * block 模式：指定方块 id 片段，半径内计数 + 最近坐标；
 * ore 模式（默认）：扫描半径内所有含 ore 的方块（含模组矿），按名字分组汇总。
 *
 * 大半径实现要点（支持 256 格）：不做暴力方块遍历，而是逐区块逐 section 用
 * LevelChunkSection.maybeHas 做调色板级预筛，只对可能命中的 section 做 16^3 细扫；
 * 未加载区块（getChunkNow 返回 null）自动跳过并在结果中说明；另有 4 秒耗时硬上限保护服务器。
 */
public class ScanWorldTool implements ITool<ScanWorldTool.Result> {
    public static final String TOOL_ID = "scan_world";
    /** 半径上限刻意保持保守：大范围扫描会显著增加服务器负载，不做 */
    private static final int MAX_RADIUS = 32;

    public record Result(String mode, String name, int radius) {
        public static final Codec<Result> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("mode", "ore").forGetter(Result::mode),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Result::name),
                Codec.INT.optionalFieldOf("radius", 16).forGetter(Result::radius)
        ).apply(i, Result::new));
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Scan the world for structures, biomes, blocks and ores. "
                + "mode 'structure' or 'biome': runs vanilla /locate with the given name (e.g. name='village' or "
                + "'minecraft:stronghold' or 'jungle') and RETURNS the located coordinates — use for 'is there a village "
                + "nearby' questions. mode 'block': counts a specific block (pass id fragment like 'diamond_ore') within "
                + "radius, returns count and nearest position. mode 'ore' (default): summarizes all ore-like blocks nearby "
                + "(vanilla + modded). Radius may be 16 or 32; for long-distance targets use mode 'structure'/'biome' "
                + "which uses /locate and costs almost nothing.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("mode", StringParameter.create()
                .setTitle("mode")
                .setDescription("structure | biome | block | ore")
                .addEnumValues("structure", "biome", "block", "ore"), false);
        root.addProperties("name", StringParameter.create()
                .setTitle("name")
                .setDescription("structure/biome id (e.g. 'village', 'minecraft:mansion', 'desert') or block id fragment (e.g. 'diamond_ore')"), false);
        root.addProperties("radius", StringParameter.create()
                .setTitle("radius")
                .setDescription("Scan radius in blocks (keep it small: 16 or 32)")
                .addEnumValues("16", "32"), false);
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
            String mode = arg.mode() == null ? "ore" : arg.mode().trim().toLowerCase();
            String name = arg.name() == null ? "" : arg.name().trim();
            String out;
            switch (mode) {
                case "structure", "biome" -> out = locate(maid, mode, name);
                case "block" -> out = scanBlocks(maid, name.toLowerCase(), arg.radius(), false);
                default -> out = scanBlocks(maid, "ore", arg.radius(), true);
            }
            com.dsh.maidbrain.MaidBrainMod.LOGGER.info("scan_world {}/'{}': ok", mode, name);
            callback.addToolResult(out, toolCallId);
        });
        return callback;
    }

    /** 用原版 /locate 指令并捕获输出 */
    private static String locate(EntityMaid maid, String kind, String name) {
        if (name.isEmpty()) {
            return "缺少 name 参数（要查找的" + (kind.equals("structure") ? "结构" : "群系") + "名）";
        }
        var server = maid.getServer();
        if (server == null) {
            return "服务器实例不可用";
        }
        List<String> captured = new ArrayList<>();
        CommandSource src = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                captured.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack stack = new CommandSourceStack(src, maid.position(), Vec2.ZERO,
                (ServerLevel) maid.level(), 4, "maid_brain", maid.getDisplayName(), server, maid);
        try {
            server.getCommands().performPrefixedCommand(stack, "locate " + kind + " " + name);
        } catch (Exception e) {
            return "locate 执行出错: " + e;
        }
        if (captured.isEmpty()) {
            return "/locate " + kind + " " + name + " 无输出（名称可能有误，试试完整 id 如 minecraft:village）";
        }
        return "locate " + kind + " " + name + ": " + String.join("; ", captured);
    }

    /**
     * 半径内方块扫描（支持到 256 格）。
     * 逐区块 section 做 maybeHas 调色板预筛，命中才细扫 16^3，避免上亿次方块访问。
     */
    private static String scanBlocks(EntityMaid maid, String needle, int radius, boolean oreMode) {
        int r = Math.max(8, Math.min(MAX_RADIUS, radius));
        if (!oreMode && needle.isEmpty()) {
            return "缺少 name 参数（要统计的方块 id 片段）";
        }
        ServerLevel level = (ServerLevel) maid.level();
        BlockPos center = maid.blockPosition();
        Predicate<BlockState> match = state -> {
            var rl = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            if (rl == null) {
                return false;
            }
            String key = rl.toString();
            return oreMode ? (key.contains("ore") && !key.contains("restore")) : key.contains(needle);
        };

        Map<String, int[]> groups = new LinkedHashMap<>(); // label -> {count, minDist, x, y, z}
        int chunkRadius = (r + 15) / 16;
        int scannedChunks = 0;
        int skippedUnloaded = 0;
        long deadline = System.currentTimeMillis() + 4000L; // 4 秒硬上限，防止卡服
        boolean truncated = false;

        outer:
        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                if (System.currentTimeMillis() > deadline) {
                    truncated = true;
                    break outer;
                }
                LevelChunk chunk = level.getChunkSource().getChunkNow(
                        (center.getX() >> 4) + cx, (center.getZ() >> 4) + cz);
                if (chunk == null) {
                    skippedUnloaded++;
                    continue;
                }
                scannedChunks++;
                LevelChunkSection[] sections = chunk.getSections();
                for (int si = 0; si < sections.length; si++) {
                    LevelChunkSection sec = sections[si];
                    if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(match)) {
                        continue;
                    }
                    int baseY = chunk.getMinBuildHeight() + (si << 4);
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState state = sec.getBlockState(x, y, z);
                                if (!match.test(state)) {
                                    continue;
                                }
                                int wx = (chunk.getPos().x << 4) + x;
                                int wy = baseY + y;
                                int wz = (chunk.getPos().z << 4) + z;
                                int dist = (int) Math.sqrt(center.distSqr(new BlockPos(wx, wy, wz)));
                                if (dist > r) {
                                    continue;
                                }
                                String label = state.getBlock().getName().getString();
                                int[] v = groups.computeIfAbsent(label, k -> new int[]{0, Integer.MAX_VALUE, 0, 0, 0});
                                v[0]++;
                                if (dist < v[1]) {
                                    v[1] = dist;
                                    v[2] = wx;
                                    v[3] = wy;
                                    v[4] = wz;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (groups.isEmpty()) {
            return r + "格半径内未发现" + (oreMode ? "矿石类方块" : "包含「" + needle + "」的方块")
                    + "（已扫描 " + scannedChunks + " 个已加载区块，跳过 " + skippedUnloaded + " 个未加载区块）"
                    + (truncated ? "；因耗时上限提前结束" : "");
        }
        List<Map.Entry<String, int[]>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort(Comparator.comparingInt(e -> e.getValue()[1]));
        StringBuilder sb = new StringBuilder("方块扫描(" + r + "格, 以女仆为中心, 已加载区块 " + scannedChunks
                + " 个, 跳过未加载 " + skippedUnloaded + " 个):");
        for (int i = 0; i < sorted.size() && i < 8; i++) {
            var e = sorted.get(i);
            int[] v = e.getValue();
            sb.append(" ").append(e.getKey()).append(" x").append(v[0])
                    .append("(最近").append(v[1]).append("格 @ ").append(v[2]).append(",").append(v[3]).append(",").append(v[4]).append(");");
        }
        if (sorted.size() > 8) {
            sb.append(" 共").append(sorted.size()).append("种");
        }
        if (truncated) {
            sb.append(" [耗时上限，结果可能不完整]");
        }
        return sb.toString();
    }
}
