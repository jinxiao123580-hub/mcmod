package com.dsh.maidbrain.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 自定义 Tool #3：整合包内部资料检索（三层）。
 * ① FTB Quests 任务书全文（config/ftbquests 的 snbt/json）；
 * ② 模组 jar 文件名匹配；
 * ③ 模组内容索引（ModContentIndex）：所有 jar 语言文件里的物品/方块/实体/流体
 *    中英文译名 → 注册 id，女仆因此认识整合包里的所有东西。
 * 检索不到时明确告知，让大脑改用 query_minecraft_wiki 等外部手段兜底。
 */
public class SearchPackGuideTool implements ITool<SearchPackGuideTool.Result> {
    public static final String TOOL_ID = "search_pack_guide";
    private static final int MAX_SNIPPETS = 8;
    private static final int MAX_LINE = 160;

    public record Result(String query) {
        public static final Codec<Result> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("query").forGetter(Result::query)
        ).apply(i, Result::new));
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Search the modpack's internal knowledge (use FIRST for any pack-content question): "
                + "(1) full-text search over the FTB Quests quest book; (2) installed mod list (jar names); "
                + "(3) a name index of EVERY item/block/entity/fluid registered by every installed mod "
                + "(both Chinese and English display names, with registry ids like 'modid:name'). "
                + "So for questions like 'what is X', 'which mod adds X', 'does the pack have X', "
                + "search the item's Chinese or English name here, then use query_recipe for how to craft it. "
                + "If nothing is found, say so and fall back to query_minecraft_wiki or your own knowledge.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("query", StringParameter.create()
                .setTitle("query")
                .setDescription("Chinese or English keyword to search in the quest book, e.g. '钢锭' or 'chapter_1'"), true);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return Result.CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result arg, LLMCallback callback) {
        callback.runOnServerThread(() -> {
            String q = arg.query() == null ? "" : arg.query().trim();
            if (q.isEmpty()) {
                callback.addToolResult("检索词为空，无法检索", toolCallId);
                return;
            }
            String needle = q.toLowerCase();
            List<String> hits = new ArrayList<>();
            Path quests = FMLPaths.GAMEDIR.get().resolve("config").resolve("ftbquests");
            if (Files.isDirectory(quests)) {
                try (Stream<Path> files = Files.walk(quests)) {
                    files.filter(p -> {
                                String n = p.getFileName().toString().toLowerCase();
                                return n.endsWith(".snbt") || n.endsWith(".json");
                            }).forEach(p -> searchInFile(p, needle, hits));
                } catch (IOException ignored) {
                }
            }
            // 模组名匹配：query 出现在 jar 文件名里也算一条
            Path mods = FMLPaths.GAMEDIR.get().resolve("mods");
            if (Files.isDirectory(mods)) {
                try (Stream<Path> files = Files.list(mods)) {
                    files.map(p -> p.getFileName().toString())
                            .filter(n -> n.toLowerCase().contains(needle) && n.endsWith(".jar"))
                            .limit(3)
                            .forEach(n -> hits.add("[mod] " + n));
                } catch (IOException ignored) {
                }
            }
            // ③ 模组内容索引：物品/方块/实体/流体的中英文译名 → 注册 id
            int itemHits = 0;
            try {
                for (String s : com.dsh.maidbrain.memory.ModContentIndex.search(q, 20)) {
                    hits.add("[item] " + s);
                    itemHits++;
                }
            } catch (Exception ex) {
                hits.add("[item] 索引暂不可用: " + ex);
            }
            String out;
            if (hits.isEmpty()) {
                out = "整合包内部资料（任务书/模组/物品索引）中未检索到「" + q + "」。"
                        + "请改用 query_minecraft_wiki 或其他方式查找，并如实告知用户。";
            } else {
                out = "整合包内部资料命中（关键词「" + q + "」，含物品索引 " + itemHits + " 条）：\n" + String.join("\n", hits);
            }
            com.dsh.maidbrain.MaidBrainMod.LOGGER.info("search_pack_guide '{}': {} hits", q, hits.size());
            callback.addToolResult(out, toolCallId);
        });
        return callback;
    }

    private static void searchInFile(Path file, String needle, List<String> hits) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String rel = file.getParent().getFileName() + "/" + file.getFileName();
            int found = 0;
            for (String line : lines) {
                if (hits.size() >= MAX_SNIPPETS) {
                    return;
                }
                String low = line.toLowerCase();
                if (low.contains(needle)) {
                    String text = line.trim();
                    if (text.length() > MAX_LINE) {
                        text = text.substring(0, MAX_LINE) + "…";
                    }
                    if (found < 2) { // 每个文件最多 2 条，避免单文件刷屏
                        hits.add("[" + rel + "] " + text);
                    }
                    found++;
                }
            }
        } catch (Exception ignored) {
            // 编码异常等，跳过该文件
        }
    }
}
