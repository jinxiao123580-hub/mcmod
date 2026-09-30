package com.dsh.maidbrain.memory;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.IMaidContext;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.List;

/**
 * 把女仆的待办清单注入每条 AI 请求的 <context>，
 * 让她始终知道自己的未完成任务，能主动汇报进度或接着干。
 */
public class MaidTaskListContext implements IMaidContext {
    public static final String KEY = "maid_tasks";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "Pending tasks";
    }

    @Override
    public String getValue(EntityMaid maid) {
        try {
            List<TaskStore.Item> pending = TaskStore.pending();
            if (pending.isEmpty()) {
                return "none";
            }
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(pending.size(), 10);
            for (int i = 0; i < limit; i++) {
                TaskStore.Item it = pending.get(i);
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append("#").append(it.id).append(" ").append(it.text);
            }
            if (pending.size() > limit) {
                sb.append(" | …共").append(pending.size()).append("项");
            }
            return sb.toString();
        } catch (Exception e) {
            return "unreadable";
        }
    }
}
