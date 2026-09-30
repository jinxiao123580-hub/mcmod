package com.dsh.maidbrain.memory;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.IMaidContext;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * 把好感度注入每条 AI 请求的 <context>。
 * 大脑据此调整语气：好感低时冷淡、可能拒绝干活；好感高时亲昵、主动帮忙。
 */
public class MaidRelationContext implements IMaidContext {
    public static final String KEY = "maid_relation";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "Affection";
    }

    @Override
    public String getValue(EntityMaid maid) {
        try {
            var uuid = maid.getOwnerUUID();
            String key = uuid == null ? "unknown" : uuid.toString();
            RelationStore.Rel rel = RelationStore.of(key);
            return rel.score + "/100 (" + RelationStore.level(rel.score) + ")"
                    + ", 已完成家务 " + rel.tasksDone
                    + ", 收到礼物 " + rel.gifts
                    + ", 上次交谈 " + RelationStore.lastTalkText(rel.lastTalk);
        } catch (Exception e) {
            return "unreadable";
        }
    }
}
