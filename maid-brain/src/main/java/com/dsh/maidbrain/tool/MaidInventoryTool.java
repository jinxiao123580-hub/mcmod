package com.dsh.maidbrain.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool #10: 女仆物流 —— 背包查看与物品流转。
 * 复用 TLM 的 getMaidInv()（Forge IItemHandler）与 Forge 的容器能力：
 * 可以查看她背了什么、把东西放进旁边的箱子、从箱子取物、或把东西交给主人。
 */
public class MaidInventoryTool implements ITool<MaidInventoryTool.Result> {
    public static final String TOOL_ID = "maid_inventory";
    private static final int CONTAINER_RADIUS = 6;

    public record Result(String mode, String item, int count) {
        public static final Codec<Result> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("mode", "list").forGetter(Result::mode),
                Codec.STRING.optionalFieldOf("item", "").forGetter(Result::item),
                Codec.INT.optionalFieldOf("count", 0).forGetter(Result::count)
        ).apply(i, Result::new));
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "The maid's backpack and item logistics. mode 'list': what she is carrying. "
                + "mode 'count' + item: how many of an item she has (id fragment like 'wheat' or Chinese name). "
                + "mode 'deposit' + optional item: move matching items (or everything if item is empty) from her backpack "
                + "into the nearest container within " + CONTAINER_RADIUS + " blocks (chest/barrel). "
                + "mode 'withdraw' + item + optional count: take items from the nearest container into her backpack. "
                + "mode 'give' + item + optional count: hand items from her backpack to the master. "
                + "Use when the master asks her to store loot, fetch materials, or hand something over.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("mode", StringParameter.create()
                .setTitle("mode")
                .setDescription("list | count | deposit | withdraw | give")
                .addEnumValues("list", "count", "deposit", "withdraw", "give"), true);
        root.addProperties("item", StringParameter.create()
                .setTitle("item")
                .setDescription("item id fragment or Chinese name, e.g. 'wheat' or '小麦'"), false);
        root.addProperties("count", IntegerParameter.create()
                .setTitle("count")
                .setDescription("how many (0 = as many as possible)")
                .setMinimum(0), false);
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
            String item = arg.item() == null ? "" : arg.item().trim();
            int count = Math.max(0, arg.count());
            String out;
            try {
                switch (mode) {
                    case "count" -> out = count_(maid, item);
                    case "deposit" -> out = deposit(maid, item);
                    case "withdraw" -> out = withdraw(maid, item, count);
                    case "give" -> out = give(maid, item, count);
                    default -> out = list(maid);
                }
            } catch (Exception e) {
                out = "物品操作出错: " + e;
            }
            com.dsh.maidbrain.MaidBrainMod.LOGGER.info("maid_inventory {}/{}: {}", mode, item, out);
            callback.addToolResult(out, toolCallId);
        });
        return callback;
    }

    private static String list(EntityMaid maid) {
        IItemHandler inv = maid.getMaidInv();
        if (inv == null) {
            return "读取不到我的背包";
        }
        Map<String, Integer> grouped = new LinkedHashMap<>();
        int used = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st.isEmpty()) {
                continue;
            }
            used++;
            grouped.merge(st.getHoverName().getString(), st.getCount(), Integer::sum);
        }
        if (grouped.isEmpty()) {
            return "我的背包是空的（共 " + inv.getSlots() + " 格）";
        }
        StringBuilder sb = new StringBuilder("我的背包（占用 " + used + "/" + inv.getSlots() + " 格）: ");
        int n = 0;
        for (Map.Entry<String, Integer> e : grouped.entrySet()) {
            if (n++ >= 20) {
                sb.append("…");
                break;
            }
            sb.append(e.getKey()).append(" x").append(e.getValue()).append(", ");
        }
        return sb.toString();
    }

    private static String count_(EntityMaid maid, String item) {
        if (item.isEmpty()) {
            return "缺少 item 参数（要查什么物品？）";
        }
        IItemHandler inv = maid.getMaidInv();
        int total = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (!st.isEmpty() && matches(st, item)) {
                total += st.getCount();
            }
        }
        return total > 0 ? "我身上有 " + item + " x" + total : "我身上没有「" + item + "」";
    }

    private static String deposit(EntityMaid maid, String item) {
        IItemHandler inv = maid.getMaidInv();
        IItemHandler box = nearestContainer(maid);
        if (box == null) {
            return "附近 " + CONTAINER_RADIUS + " 格内没有找到箱子/容器，没法存放。";
        }
        int moved = 0;
        int stacks = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st.isEmpty() || (!item.isEmpty() && !matches(st, item))) {
                continue;
            }
            int before = st.getCount();
            ItemStack taken = inv.extractItem(i, before, false);
            if (taken.isEmpty()) {
                continue;
            }
            ItemStack rest = ItemHandlerHelper.insertItemStacked(box, taken, false);
            int movedNow = taken.getCount() - rest.getCount();
            moved += movedNow;
            if (movedNow > 0) {
                stacks++;
            }
            if (!rest.isEmpty()) {
                inv.insertItem(i, rest, false);
            }
        }
        return moved > 0 ? "已把 " + moved + " 个物品（" + stacks + " 组）放进旁边箱子里"
                : "箱子里放不下或我没有可存放的物品";
    }

    private static String withdraw(EntityMaid maid, String item, int count) {
        if (item.isEmpty()) {
            return "缺少 item 参数（要从箱子取什么？）";
        }
        IItemHandler inv = maid.getMaidInv();
        IItemHandler box = nearestContainer(maid);
        if (box == null) {
            return "附近 " + CONTAINER_RADIUS + " 格内没有箱子/容器。";
        }
        int want = count <= 0 ? Integer.MAX_VALUE : count;
        int got = 0;
        for (int i = 0; i < box.getSlots() && got < want; i++) {
            ItemStack st = box.getStackInSlot(i);
            if (st.isEmpty() || !matches(st, item)) {
                continue;
            }
            int take = Math.min(want - got, st.getCount());
            ItemStack taken = box.extractItem(i, take, false);
            if (taken.isEmpty()) {
                continue;
            }
            ItemStack rest = ItemHandlerHelper.insertItemStacked(inv, taken, false);
            got += taken.getCount() - rest.getCount();
            if (!rest.isEmpty()) {
                ItemHandlerHelper.insertItemStacked(box, rest, false);
            }
        }
        return got > 0 ? "已从箱子取到 " + item + " x" + got + " 放进我的背包"
                : "箱子里没有找到「" + item + "」或我的背包已满";
    }

    private static String give(EntityMaid maid, String item, int count) {
        if (item.isEmpty()) {
            return "缺少 item 参数（要交给主人什么？）";
        }
        ServerPlayer owner = ownerOf(maid);
        if (owner == null) {
            return "主人不在附近，没法递交物品";
        }
        IItemHandler inv = maid.getMaidInv();
        int want = count <= 0 ? Integer.MAX_VALUE : count;
        int given = 0;
        for (int i = 0; i < inv.getSlots() && given < want; i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st.isEmpty() || !matches(st, item)) {
                continue;
            }
            int take = Math.min(want - given, st.getCount());
            ItemStack taken = inv.extractItem(i, take, false);
            if (taken.isEmpty()) {
                continue;
            }
            if (!owner.getInventory().add(taken)) {
                // 主人背包满，剩下的还给自己
                inv.insertItem(i, taken, false);
                break;
            }
            given += take;
        }
        return given > 0 ? "已把 " + item + " x" + given + " 交给主人"
                : "我身上没有「" + item + "」或主人背包已满";
    }

    /** 找女仆附近最近的可存物容器（箱子/木桶/潜影盒等，走 Forge 物品能力） */
    private static IItemHandler nearestContainer(EntityMaid maid) {
        ServerLevel level = (ServerLevel) maid.level();
        BlockPos center = maid.blockPosition();
        IItemHandler best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(
                center.offset(-CONTAINER_RADIUS, -2, -CONTAINER_RADIUS),
                center.offset(CONTAINER_RADIUS, 2, CONTAINER_RADIUS))) {
            BlockEntity be = level.getBlockEntity(p);
            if (be == null) {
                continue;
            }
            IItemHandler handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).orElse(null);
            if (handler == null || handler.getSlots() <= 0) {
                continue;
            }
            double d = p.distSqr(center);
            if (d < bestDist) {
                bestDist = d;
                best = handler;
            }
        }
        return best;
    }

    private static boolean matches(ItemStack stack, String query) {
        String q = query.toLowerCase();
        var rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (rl != null && rl.toString().toLowerCase().contains(q)) {
            return true;
        }
        return stack.getHoverName().getString().contains(query);
    }

    private static ServerPlayer ownerOf(EntityMaid maid) {
        try {
            var server = maid.getServer();
            var uuid = maid.getOwnerUUID();
            if (server == null || uuid == null) {
                return null;
            }
            return server.getPlayerList().getPlayer(uuid);
        } catch (Exception e) {
            return null;
        }
    }
}
