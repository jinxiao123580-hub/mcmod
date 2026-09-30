package com.dsh.maidbrain;

import com.dsh.maidbrain.tool.ScoutReportTool;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;

/**
 * maid_brain 扩展入口。由 TLM 通过 @LittleMaidExtension 注解自动发现。
 * 注册自定义 AI Tool：女仆大脑（DSH 会话 agent）可调用的侦察报告。
 */
@LittleMaidExtension
public class MaidBrainExtension implements ILittleMaid {

    @Override
    public void registerAITool(ToolRegister register) {
        register.register(new ScoutReportTool());
        register.register(new com.dsh.maidbrain.tool.RunCommandTool());
        register.register(new com.dsh.maidbrain.tool.SearchPackGuideTool());
        register.register(new com.dsh.maidbrain.tool.QueryRecipeTool());
        register.register(new com.dsh.maidbrain.tool.ScanWorldTool());
        register.register(new com.dsh.maidbrain.tool.MemoryTool());
        register.register(new com.dsh.maidbrain.tool.WorkTool());
        register.register(new com.dsh.maidbrain.tool.TaskListTool());
        register.register(new com.dsh.maidbrain.tool.WaypointTool());
        register.register(new com.dsh.maidbrain.tool.MaidInventoryTool());
        register.register(new com.dsh.maidbrain.tool.RelationTool());
        MaidBrainMod.LOGGER.info("Registered 11 maid_brain tools: scout_report / run_command / search_pack_guide / query_recipe / scan_world / maid_memory / do_work / maid_tasks / maid_waypoint / maid_inventory / maid_relation");
    }

    @Override
    public void registerAIMaidContext(com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister register) {
        register.registerCategory(com.dsh.maidbrain.memory.MaidMemoryContext.KEY, "Maid long-term memory", true);
        register.registerContext(com.dsh.maidbrain.memory.MaidMemoryContext.KEY, new com.dsh.maidbrain.memory.MaidMemoryContext());
        register.registerCategory(com.dsh.maidbrain.memory.MaidTaskListContext.KEY, "Maid pending tasks", true);
        register.registerContext(com.dsh.maidbrain.memory.MaidTaskListContext.KEY, new com.dsh.maidbrain.memory.MaidTaskListContext());
        register.registerCategory(com.dsh.maidbrain.memory.MaidRelationContext.KEY, "Affection toward master", true);
        register.registerContext(com.dsh.maidbrain.memory.MaidRelationContext.KEY, new com.dsh.maidbrain.memory.MaidRelationContext());
        MaidBrainMod.LOGGER.info("Registered maid_memory + maid_tasks + maid_relation prompt contexts (maid_brain)");
    }
}
