package com.dsh.maidbrain.tool;

import com.dsh.maidbrain.memory.WaypointStore;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool #9: 地标系统 —— 命名地点记忆与到达。
 * save：把主人（或女仆）当前所在位置存为命名地标；
 * list：列出所有地标；
 * goto：女仆寻路走过去（仅同维度，复用原版寻路）；
 * tp：用 /tp 指令把女仆瞬移过去（支持跨维度，复用 run_command 的指令+输出捕获思路）；
 * tp_player：把主人瞬移过去（/tp @p）；
 * remove：删除地标。
 */
public class WaypointTool implements ITool<WaypointTool.Result> {
    public static final String TOOL_ID = "maid_waypoint";

    public record Result(String mode, String name) {
        public static final Codec<Result> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("mode", "list").forGetter(Result::mode),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Result::name)
        ).apply(i, Result::new));
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Named-place memory (waypoints) shared with the master, persisted across sessions. "
                + "mode 'save' + name: remember the master's CURRENT position under that name — use when he says "
                + "'remember this place is home' / '记住这里叫家'. mode 'list': list all saved places. "
                + "mode 'goto' + name: walk there (same dimension only). "
                + "mode 'tp' + name: teleport the maid there instantly (works across dimensions). "
                + "mode 'tp_player' + name: teleport the MASTER there instantly. "
                + "mode 'remove' + name: forget a place.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("mode", StringParameter.create()
                .setTitle("mode")
                .setDescription("save | list | goto | tp | tp_player | remove")
                .addEnumValues("save", "list", "goto", "tp", "tp_player", "remove"), true);
        root.addProperties("name", StringParameter.create()
                .setTitle("name")
                .setDescription("place name, e.g. '家' or 'home' or '矿洞'"), false);
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
            String mode = arg.mode() == null ? "list" : arg.mode().trim().toLowerCase();
            String name = arg.name() == null ? "" : arg.name().trim();
            String out;
            switch (mode) {
                case "save" -> out = save(maid, name);
                case "remove" -> out = remove(name);
                case "goto" -> out = goTo(maid, name);
                case "tp" -> out = teleport(maid, name, false);
                case "tp_player" -> out = teleport(maid, name, true);
                default -> out = list();
            }
            com.dsh.maidbrain.MaidBrainMod.LOGGER.info("maid_waypoint {}/{}: {}", mode, name, out);
            callback.addToolResult(out, toolCallId);
        });
        return callback;
    }

    private static String save(EntityMaid maid, String name) {
        if (name.isEmpty()) {
            return "缺少 name 参数（要给这个地方起什么名字？）";
        }
        ServerPlayer owner = ownerOf(maid);
        // 「这里」= 主人当前位置；主人不在则退化为女仆位置
        double px;
        double py;
        double pz;
        String who;
        if (owner != null) {
            px = owner.getX();
            py = owner.getY();
            pz = owner.getZ();
            who = "主人所在位置";
        } else {
            px = maid.getX();
            py = maid.getY();
            pz = maid.getZ();
            who = "我所在位置";
        }
        String dim = maid.level().dimension().location().toString();
        boolean isNew = WaypointStore.save(name, dim, (int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz));
        WaypointStore.Wp wp = WaypointStore.find(name);
        String coord = wp == null ? "" : ("(" + wp.x + "," + wp.y + "," + wp.z + ")");
        return (isNew ? "已记住新地标「" + name + "」" : "已更新地标「" + name + "」")
                + "：" + who + " " + coord + " 维度 " + dim;
    }

    private static String list() {
        List<WaypointStore.Wp> all = WaypointStore.all();
        if (all.isEmpty()) {
            return "还没有任何地标。让主人说「记住这里叫家」我就记下来。";
        }
        StringBuilder sb = new StringBuilder("地标(" + all.size() + "): ");
        for (WaypointStore.Wp wp : all) {
            sb.append(wp.name).append("(").append(wp.x).append(",").append(wp.y).append(",").append(wp.z)
                    .append(" @").append(shortDim(wp.dim)).append("); ");
        }
        return sb.toString();
    }

    private static String remove(String name) {
        WaypointStore.Wp wp = WaypointStore.remove(name);
        return wp == null ? "没有找到地标「" + name + "」" : "已删除地标「" + wp.name + "」";
    }

    private static String goTo(EntityMaid maid, String name) {
        WaypointStore.Wp wp = WaypointStore.find(name);
        if (wp == null) {
            return "没有找到地标「" + name + "」。可以说「记住这里叫" + name + "」先记下来。";
        }
        String here = maid.level().dimension().location().toString();
        if (!here.equals(wp.dim)) {
            return "地标「" + wp.name + "」在另一个维度(" + shortDim(wp.dim) + ")，走路去不了，"
                    + "需要我瞬移过去可以说「瞬移去" + wp.name + "」。";
        }
        if (maid.isInSittingPose()) {
            maid.setInSittingPose(false);
        }
        double dist = Math.sqrt(maid.distanceToSqr(wp.x + 0.5, wp.y, wp.z + 0.5));
        boolean ok = maid.getNavigation().moveTo(wp.x + 0.5, wp.y, wp.z + 0.5, 1.0D);
        return ok ? "开始前往「" + wp.name + "」(" + wp.x + "," + wp.y + "," + wp.z + ")，距离约 " + (int) dist + " 格。"
                : "去「" + wp.name + "」寻路失败（可能太远或地形不可达），可以改用瞬移。";
    }

    private static String teleport(EntityMaid maid, String name, boolean playerTarget) {
        WaypointStore.Wp wp = WaypointStore.find(name);
        if (wp == null) {
            return "没有找到地标「" + name + "」";
        }
        var server = maid.getServer();
        if (server == null) {
            return "服务器实例不可用，无法传送";
        }
        String who = playerTarget ? "@p" : "@s";
        String cmd = "execute in " + wp.dim + " run tp " + who + " " + wp.x + " " + wp.y + " " + wp.z;
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
            int code = server.getCommands().performPrefixedCommand(stack, cmd);
            if (code <= 0 && !captured.isEmpty()) {
                return "传送「" + wp.name + "」失败: " + String.join("; ", captured);
            }
        } catch (Exception e) {
            return "传送出错: " + e;
        }
        return (playerTarget ? "已把主人传送到「" + wp.name + "」" : "我已瞬移到「" + wp.name + "」")
                + " (" + wp.x + "," + wp.y + "," + wp.z + " @ " + shortDim(wp.dim) + ")";
    }

    private static ServerPlayer ownerOf(EntityMaid maid) {
        try {
            var server = maid.getServer();
            if (server == null) {
                return null;
            }
            var uuid = maid.getOwnerUUID();
            if (uuid == null) {
                return null;
            }
            return server.getPlayerList().getPlayer(uuid);
        } catch (Exception e) {
            return null;
        }
    }

    private static String shortDim(String dim) {
        int i = dim.indexOf(':');
        return i < 0 ? dim : dim.substring(i + 1);
    }
}
