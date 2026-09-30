package com.dsh.maidbrain.tool;

import com.dsh.maidbrain.memory.RelationStore;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Tool #11: 好感度/关系。
 * status 汇报当前关系；praise/scold/gift 由大脑在识别到主人的社交行为时调用，
 * 分别 +3 / -5 / +5。好感度会注入提示词，并影响她是否愿意干活（见 WorkTool）。
 */
public class RelationTool implements ITool<RelationTool.Result> {
    public static final String TOOL_ID = "maid_relation";

    public record Result(String mode) {
        public static final Codec<Result> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("mode", "status").forGetter(Result::mode)
        ).apply(i, Result::new));
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "The maid's affection toward the master (0-100), which shapes her tone and willingness to work. "
                + "It rises when she completes chores and when the master talks to her, and falls if the master hits her. "
                + "mode 'status': report the current relationship. mode 'praise': +3 — call when the master compliments, "
                + "thanks or encourages her. mode 'scold': -5 — call when the master is angry or scolds her. "
                + "mode 'gift': +5 — call when the master gives her an item as a present.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("mode", StringParameter.create()
                .setTitle("mode")
                .setDescription("status | praise | scold | gift")
                .addEnumValues("status", "praise", "scold", "gift"), true);
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
            var uuid = maid.getOwnerUUID();
            String key = uuid == null ? "unknown" : uuid.toString();
            String mode = arg.mode() == null ? "status" : arg.mode().trim().toLowerCase();
            String out;
            switch (mode) {
                case "praise" -> {
                    int s = RelationStore.add(key, 3);
                    out = "被主人夸奖了，好感度 +3 → " + s + "/100（" + RelationStore.level(s) + "）";
                }
                case "scold" -> {
                    int s = RelationStore.add(key, -5);
                    out = "被主人责备了，好感度 -5 → " + s + "/100（" + RelationStore.level(s) + "）";
                }
                case "gift" -> {
                    RelationStore.countGift(key, 5);
                    int s = RelationStore.score(key);
                    out = "收到主人的礼物，好感度 +5 → " + s + "/100（" + RelationStore.level(s) + "）";
                }
                default -> {
                    RelationStore.Rel rel = RelationStore.of(key);
                    out = "对主人的好感度: " + rel.score + "/100（" + RelationStore.level(rel.score) + "）"
                            + "; 已完成家务 " + rel.tasksDone + " 件; 收到礼物 " + rel.gifts + " 次; 上次交谈 "
                            + RelationStore.lastTalkText(rel.lastTalk);
                }
            }
            com.dsh.maidbrain.MaidBrainMod.LOGGER.info("maid_relation {}: {}", mode, out);
            callback.addToolResult(out, toolCallId);
        });
        return callback;
    }
}
