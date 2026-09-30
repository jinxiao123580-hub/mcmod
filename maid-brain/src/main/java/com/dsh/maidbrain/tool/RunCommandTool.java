package com.dsh.maidbrain.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 自定义 Tool #2：内置指令执行器。
 * 大脑可让女仆以管理员权限执行任意 Minecraft 指令：
 * 控制时间（time set day/night）、天气（weather clear）、召唤生物（summon）、
 * 传送（tp）、游戏模式（gamemode）、给予物品（give）等玩家可用指令。
 * 安全阀：拒绝执行 stop / ban / kick 类毁灭性或踢人指令。
 */
public class RunCommandTool implements ITool<RunCommandTool.Result> {
    public static final String TOOL_ID = "run_command";

    public record Result(String command) {
        public static final Codec<Result> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("command").forGetter(Result::command)
        ).apply(i, Result::new));
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Run a Minecraft operator command to change the world: e.g. 'time set day', 'time set night', "
                + "'weather clear', 'summon minecraft:wolf ~ ~ ~', 'tp @s <x> <y> <z>', 'gamemode creative', 'give @s bread 8'. "
                + "Use this when the user asks to change time, weather, summon or remove mobs, teleport, change gamemode, "
                + "give items, or anything a player command can do. Pass the command WITHOUT the leading slash.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("command", StringParameter.create()
                .setTitle("command")
                .setDescription("Minecraft command without leading slash, e.g. 'time set day'"), true);
        // 注意: addProperties(..., true) 已把参数加入 required，重复调用 addRequired 会产生
        // ["command","command"] 这样的重复项，DeepSeek 等严格校验的 API 会直接 400 拒绝。
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
            String cmd = arg.command() == null ? "" : arg.command().trim();
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            String head = cmd.split("\\s+")[0].toLowerCase();
            // 安全阀：拒绝毁灭性/踢人指令
            if (head.equals("stop") || head.equals("ban") || head.equals("ban-ip")
                    || head.equals("banlist") || head.equals("kick")) {
                callback.addToolResult("已拒绝执行受限指令: /" + cmd + "（stop/ban/kick 类指令不被允许）", toolCallId);
                return;
            }
            var server = maid.getServer();
            if (server == null) {
                callback.addToolResult("服务器实例不可用，无法执行指令", toolCallId);
                return;
            }
            var source = server.createCommandSourceStack()
                    .withPermission(4)
                    .withPosition(maid.position())
                    .withSuppressedOutput();
            String out;
            try {
                int code = server.getCommands().performPrefixedCommand(source, cmd);
                out = code > 0 ? "指令已执行成功: /" + cmd : "指令已运行但无效果或参数有误: /" + cmd + "（code=" + code + "）";
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                out = "指令执行出错: /" + cmd + " → " + msg;
            }
            com.dsh.maidbrain.MaidBrainMod.LOGGER.info("run_command: /{} → {}", cmd, out);
            callback.addToolResult(out, toolCallId);
        });
        return callback;
    }
}
