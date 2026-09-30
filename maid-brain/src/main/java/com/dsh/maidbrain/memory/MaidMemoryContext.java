package com.dsh.maidbrain.memory;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.IMaidContext;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.List;

/**
 * 把长期记忆注入每条 AI 请求的 <context>（官方 GameContextRegister 机制）。
 * 只取最近 12 条 + 总数，避免撑爆提示词。
 */
public class MaidMemoryContext implements IMaidContext {
    public static final String KEY = "maid_memory";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "Long-term memory";
    }

    @Override
    public String getValue(EntityMaid maid) {
        try {
            List<String> all = MemoryStore.all();
            if (all.isEmpty()) {
                return "none";
            }
            int from = Math.max(0, all.size() - 12);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < all.size(); i++) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(all.get(i));
            }
            return "(" + all.size() + " notes, latest: ) " + sb;
        } catch (Exception e) {
            return "unreadable";
        }
    }
}
