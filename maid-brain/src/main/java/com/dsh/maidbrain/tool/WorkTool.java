package com.dsh.maidbrain.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Tool #7: do_work — 让女仆真的动起来干活。
 * 复用 TLM 自带的工作行为（IMaidTask）：农场/甘蔗/采蜜/剪羊毛/挤奶/钓鱼/火把/灭火/喂食/繁殖/清雪/花草/攻击……
 * 切换任务后女仆的 brain 会自主寻路并执行，不需要我们自己写寻路。
 * 另有 goto（走到坐标）、status（在干什么）、stop（回空闲）三个模式。
 */
public class WorkTool implements ITool<WorkTool.Result> {
    public static final String TOOL_ID = "do_work";
    private static final String DEFAULT_NS = "touhou_little_maid";

    public record Result(String mode, String task, int x, int y, int z) {
        public static final Codec<Result> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("mode", "status").forGetter(Result::mode),
                Codec.STRING.optionalFieldOf("task", "").forGetter(Result::task),
                Codec.INT.optionalFieldOf("x", 0).forGetter(Result::x),
                Codec.INT.optionalFieldOf("y", 0).forGetter(Result::y),
                Codec.INT.optionalFieldOf("z", 0).forGetter(Result::z)
        ).apply(i, Result::new));
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Make the maid ACTUALLY DO work or move. "
                + "mode 'task' + task=<name>: switch her work behaviour and she will then autonomously work nearby — "
                + "farm(农场 收割作物), sugar_cane(甘蔗), melon(瓜类), cocoa(可可), honey(采蜜), grass(花草), snow(清雪), "
                + "feed(喂食), feed_animal(繁殖动物), shears(剪羊毛), milk(牛奶), torch(火把), extinguishing(灭火), "
                + "fishing(钓鱼), attack(攻击敌人), ranged_attack(弓兵), danmaku_attack(弹幕), idle(空闲). "
                + "mode 'list': list every available work behaviour. mode 'goto' + x/y/z: walk to those coordinates. "
                + "mode 'status': report what she is doing right now. mode 'stop': back to idle and stop moving. "
                + "Use this whenever the user asks her to work, harvest, tend animals, fight, or go somewhere.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("mode", StringParameter.create()
                .setTitle("mode")
                .setDescription("task | list | goto | status | stop")
                .addEnumValues("task", "list", "goto", "status", "stop"), true);
        root.addProperties("task", StringParameter.create()
                .setTitle("task")
                .setDescription("work id or Chinese name, e.g. 'farm' or '农场'"), false);
        root.addProperties("x", IntegerParameter.create().setTitle("x").setDescription("target X (mode goto)"), false);
        root.addProperties("y", IntegerParameter.create().setTitle("y").setDescription("target Y (mode goto)"), false);
        root.addProperties("z", IntegerParameter.create().setTitle("z").setDescription("target Z (mode goto)"), false);
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
            String mode = arg.mode() == null ? "status" : arg.mode().trim().toLowerCase();
            String out;
            switch (mode) {
                case "task" -> out = switchTask(maid, arg.task());
                case "list" -> out = listTasks();
                case "goto" -> out = goTo(maid, arg.x(), arg.y(), arg.z());
                case "stop" -> out = stop(maid);
                default -> out = status(maid);
            }
            com.dsh.maidbrain.MaidBrainMod.LOGGER.info("do_work {}/{}: {}", mode, arg.task(), out);
            callback.addToolResult(out, toolCallId);
        });
        return callback;
    }

    private static String switchTask(EntityMaid maid, String raw) {
        String input = raw == null ? "" : raw.trim();
        if (input.isEmpty()) {
            return "缺少 task 参数。可用模式: " + briefList();
        }
        IMaidTask found = null;
        if (input.contains(":")) {
            ResourceLocation rl = ResourceLocation.tryParse(input);
            if (rl != null) {
                found = TaskManager.findTask(rl).orElse(null);
            }
        }
        if (found == null) {
            found = TaskManager.findTask(new ResourceLocation(DEFAULT_NS, input)).orElse(null);
        }
        if (found == null) {
            for (IMaidTask t : TaskManager.getTaskIndex()) {
                String path = t.getUid().getPath();
                String cn = t.getName().getString();
                if (path.equalsIgnoreCase(input) || path.contains(input.toLowerCase())
                        || cn.equals(input) || cn.contains(input)) {
                    found = t;
                    break;
                }
            }
        }
        if (found == null) {
            return "没有名为「" + input + "」的工作模式。可用: " + briefList();
        }
        // 好感度门槛：关系冷淡时她会闹脾气拒绝干活（好感度由互动/家务/被打事件维护）
        int score = com.dsh.maidbrain.memory.RelationStore.score(ownerKey(maid));
        if (score <= 20) {
            return "（她抱着手臂别过头去）主人最近都不怎么理我……我现在不想干活。"
                    + "（好感度 " + score + "/100，和她说说话、夸夸她或许会好一些）";
        }
        maid.setTask(found);
        if (maid.isInSittingPose()) {
            maid.setInSittingPose(false);
        }
        return "已切换到「" + found.getName().getString() + "」工作模式（id: " + found.getUid()
                + "），我会在附近自主开始干活，主人问「在干什么」可以随时确认。";
    }

    private static String listTasks() {
        List<IMaidTask> all = TaskManager.getTaskIndex();
        StringBuilder sb = new StringBuilder("可用工作模式(" + all.size() + "): ");
        for (IMaidTask t : all) {
            sb.append(t.getName().getString()).append("[").append(t.getUid().getPath()).append("] ");
        }
        return sb.toString();
    }

    /** 好感度存储用的键（主人 UUID） */
    private static String ownerKey(EntityMaid maid) {
        var uuid = maid.getOwnerUUID();
        return uuid == null ? "unknown" : uuid.toString();
    }

    private static String briefList() {        StringBuilder sb = new StringBuilder();
        for (IMaidTask t : TaskManager.getTaskIndex()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t.getUid().getPath());
        }
        return sb.toString();
    }

    private static String goTo(EntityMaid maid, int x, int y, int z) {
        if (maid.isInSittingPose()) {
            maid.setInSittingPose(false);
        }
        double dist = Math.sqrt(maid.distanceToSqr(x + 0.5, y, z + 0.5));
        boolean ok = maid.getNavigation().moveTo(x + 0.5, y, z + 0.5, 1.0D);
        if (!ok) {
            return "寻路失败：(" + x + "," + y + "," + z + ") 可能不可达或过远（距离约 " + (int) dist + " 格）。"
                    + "可先用 scan_world 确认地形，或改让主人靠近一些。";
        }
        return "开始前往 (" + x + "," + y + "," + z + ")，距离约 " + (int) dist + " 格，到了我再汇报。";
    }

    private static String stop(EntityMaid maid) {
        maid.getNavigation().stop();
        maid.setTask(TaskManager.getIdleTask());
        return "已停止移动并回到「空闲」状态。";
    }

    private static String status(EntityMaid maid) {
        IMaidTask task = maid.getTask();
        String action = null;
        try {
            action = task.getMaidActionSummary();
        } catch (Exception ignored) {
        }
        return "当前工作模式: " + task.getName().getString() + "[" + task.getUid().getPath() + "]"
                + "; 当前动作: " + (action == null || action.isEmpty() ? "无" : action)
                + "; 位置: (" + maid.getBlockX() + "," + maid.getBlockY() + "," + maid.getBlockZ() + ")"
                + "; 正在移动: " + (!maid.getNavigation().isDone())
                + "; 坐姿: " + maid.isInSittingPose();
    }
}
