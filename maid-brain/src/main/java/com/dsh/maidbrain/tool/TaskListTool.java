package com.dsh.maidbrain.tool;

import com.dsh.maidbrain.memory.TaskStore;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * Tool #8: personal to-do list of the maid (add / list / done / remove / clear).
 * The pending list is auto-injected into every prompt via MaidTaskListContext,
 * so she always knows what she still owes the master. Works together with do_work.
 */
public class TaskListTool implements ITool<TaskListTool.Result> {
    public static final String TOOL_ID = "maid_tasks";

    public record Result(String mode, String text) {
        public static final Codec<Result> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("mode", "list").forGetter(Result::mode),
                Codec.STRING.optionalFieldOf("text", "").forGetter(Result::text)
        ).apply(i, Result::new));
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "The maid's own to-do list, persisted and auto-injected into every prompt (so she remembers what she owes). "
                + "mode 'add' + text: record a task — use when the master assigns a chore or when you promise something "
                + "(e.g. 'harvest the wheat field', 'bring 8 bread'). mode 'list': show all tasks with ids. "
                + "mode 'done' + text: mark finished (accepts '#3' or a text fragment) — call this right after you complete "
                + "a chore. mode 'remove' + text: drop a task. mode 'clear': delete all finished tasks.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("mode", StringParameter.create()
                .setTitle("mode")
                .setDescription("add | list | done | remove | clear")
                .addEnumValues("add", "list", "done", "remove", "clear"), false);
        root.addProperties("text", StringParameter.create()
                .setTitle("text")
                .setDescription("task text (mode add) or '#id'/fragment (mode done/remove)"), false);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return Result.CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result arg, LLMCallback callback) {
        callback.runOnServerThread(() -> {
            String mode = arg.mode() == null ? "list" : arg.mode().trim().toLowerCase();
            String text = arg.text() == null ? "" : arg.text().trim();
            String out;
            switch (mode) {
                case "add" -> {
                    int id = TaskStore.add(text);
                    out = id < 0 ? "任务内容为空，未记录"
                            : "已记入待办 #" + id + ": " + text + "（当前未完成 " + TaskStore.pending().size() + " 项）";
                }
                case "done" -> {
                    String hit = TaskStore.markDone(text);
                    if (hit == null) {
                        out = "没找到匹配的待办: " + text;
                    } else {
                        var uuid = callback.getMaid().getOwnerUUID();
                        String key = uuid == null ? "unknown" : uuid.toString();
                        com.dsh.maidbrain.memory.RelationStore.countTask(key, 2);
                        int s = com.dsh.maidbrain.memory.RelationStore.score(key);
                        out = "已完成: " + hit + "（剩余未完成 " + TaskStore.pending().size()
                                + " 项；主人会高兴的，好感度 → " + s + "）";
                    }
                }
                case "remove" -> {
                    String hit = TaskStore.remove(text);
                    out = hit == null ? "没找到匹配的待办: " + text : "已删除待办: " + hit;
                }
                case "clear" -> out = "已清除 " + TaskStore.clearDone() + " 项已完成任务";
                default -> {
                    List<TaskStore.Item> all = TaskStore.all();
                    if (all.isEmpty()) {
                        out = "待办清单是空的";
                    } else {
                        StringBuilder sb = new StringBuilder("待办清单(" + TaskStore.pending().size() + " 项未完成): ");
                        for (TaskStore.Item it : all) {
                            sb.append("#").append(it.id).append(it.done ? " [已完成] " : " [ ] ").append(it.text).append("; ");
                        }
                        out = sb.toString();
                    }
                }
            }
            com.dsh.maidbrain.MaidBrainMod.LOGGER.info("maid_tasks {}/{}: {}", mode, text, out);
            callback.addToolResult(out, toolCallId);
        });
        return callback;
    }
}
