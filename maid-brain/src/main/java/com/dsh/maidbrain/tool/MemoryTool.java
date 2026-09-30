package com.dsh.maidbrain.tool;

import com.dsh.maidbrain.memory.MemoryStore;
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
 * Tool #6: maid long-term memory (save / forget / read).
 * Saved facts are auto-injected into every future prompt via MaidMemoryContext.
 */
public class MemoryTool implements ITool<MemoryTool.Result> {
    public static final String TOOL_ID = "maid_memory";

    public record Result(String mode, String content, String query) {
        public static final Codec<Result> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("mode", "read").forGetter(Result::mode),
                Codec.STRING.optionalFieldOf("content", "").forGetter(Result::content),
                Codec.STRING.optionalFieldOf("query", "").forGetter(Result::query)
        ).apply(i, Result::new));
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Long-term memory of this maid, persisted across game sessions and auto-injected into every future prompt. "
                + "mode 'save': store a durable fact about the master or agreements — e.g. preferences ('Master dislikes "
                + "creepers'), promises ('agreed to mine 20 iron tonight'), important events. Save ONLY things worth "
                + "remembering for days, not small talk. mode 'forget': delete notes containing a fragment. mode 'read': "
                + "list notes, optionally filtered by query.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("mode", StringParameter.create()
                .setTitle("mode")
                .setDescription("save | forget | read")
                .addEnumValues("save", "forget", "read"), false);
        root.addProperties("content", StringParameter.create()
                .setTitle("content")
                .setDescription("The fact to remember (for save) or fragment to delete (for forget)"), false);
        root.addProperties("query", StringParameter.create()
                .setTitle("query")
                .setDescription("Optional filter for read"), false);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return Result.CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result arg, LLMCallback callback) {
        callback.runOnServerThread(() -> {
            String mode = arg.mode() == null ? "read" : arg.mode().trim().toLowerCase();
            String out;
            switch (mode) {
                case "save" -> {
                    MemoryStore.add(arg.content());
                    out = "已记住: " + arg.content() + "（今后每次对话都会自动想起）";
                    com.dsh.maidbrain.MaidBrainMod.LOGGER.info("maid_memory saved: {}", arg.content());
                }
                case "forget" -> {
                    int n = MemoryStore.forget(arg.content());
                    out = n > 0 ? "已遗忘 " + n + " 条相关记忆" : "没有匹配的记忆";
                }
                default -> {
                    List<String> hits = MemoryStore.find(arg.query());
                    out = hits.isEmpty() ? "（长期记忆为空）"
                            : "长期记忆 " + hits.size() + " 条:\n- " + String.join("\n- ", hits);
                }
            }
            callback.addToolResult(out, toolCallId);
        });
        return callback;
    }
}
