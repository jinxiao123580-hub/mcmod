package com.dsh.maidbrain;

import com.dsh.maidbrain.memory.LoadedStore;
import com.dsh.maidbrain.memory.RelationStore;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.List;

/**
 * 指令层：
 * /maidchat <消息>   —— 全图与自己的女仆对话（不限距离、跨维度也可找到她），
 *                       复用 ChatBarBridge 同款 TLM 聊天管线，回复会以聊天消息发回给你；
 * /maidload [on|off] —— 开关最近女仆的「常加载」（她所在区块强制加载，人不在也继续干活）。
 */
@Mod.EventBusSubscriber(modid = MaidBrainMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MaidCommands {
    private MaidCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("maidchat")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String text = StringArgumentType.getString(ctx, "message");
                            EntityMaid maid = findAnywhere(player);
                            if (maid == null) {
                                ctx.getSource().sendFailure(Component.literal("没有找到属于你的女仆"));
                                return 0;
                            }
                            maid.getAiChatManager().chat(text, ChatClientInfo.fromMaid(maid), player);
                            // 与聊天栏同款：交谈时间戳 + 节流涨好感
                            String key = player.getUUID().toString();
                            long last = RelationStore.of(key).lastTalk;
                            RelationStore.touchTalk(key);
                            if (System.currentTimeMillis() - last > 300_000L) {
                                RelationStore.add(key, 1);
                            }
                            MaidBrainMod.LOGGER.info("[maidchat] {} -> maid {}", text, maid.getUUID());
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("maidload")
                .executes(ctx -> toggle(ctx.getSource().getPlayerOrException(), null))
                .then(Commands.literal("on")
                        .executes(ctx -> toggle(ctx.getSource().getPlayerOrException(), Boolean.TRUE)))
                .then(Commands.literal("off")
                        .executes(ctx -> toggle(ctx.getSource().getPlayerOrException(), Boolean.FALSE))));
    }

    private static int toggle(ServerPlayer player, Boolean set) {
        ServerLevel level = player.serverLevel();
        EntityMaid maid = null;
        double best = Double.MAX_VALUE;
        for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (e instanceof EntityMaid m && m.isAlive() && m.isOwnedBy(player)) {
                double d = m.distanceToSqr(player);
                if (d < best) {
                    best = d;
                    maid = m;
                }
            }
        }
        if (maid == null) {
            player.sendSystemMessage(Component.literal("附近没有你的女仆"));
            return 0;
        }
        String id = maid.getStringUUID();
        boolean on = set != null ? set : !LoadedStore.contains(id);
        if (on) {
            LoadedStore.add(id);
            MaidChunkLoader.track(maid);
            player.sendSystemMessage(Component.literal("已开启常加载：你不在时她也会继续干活（跟随她所在区块）"));
        } else {
            LoadedStore.remove(id);
            MaidChunkLoader.release(maid);
            player.sendSystemMessage(Component.literal("已关闭常加载"));
        }
        return 1;
    }

    /** 全图找女仆：优先同维度最近，否则任意维度第一只 */
    private static EntityMaid findAnywhere(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        EntityMaid best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                if (!(e instanceof EntityMaid m) || !m.isAlive() || !m.isOwnedBy(player)) {
                    continue;
                }
                if (level.dimension() == player.level().dimension()) {
                    double d = m.distanceToSqr(player);
                    if (d < bestDist) {
                        bestDist = d;
                        best = m;
                    }
                } else if (best == null) {
                    best = m;
                }
            }
        }
        return best;
    }
}
