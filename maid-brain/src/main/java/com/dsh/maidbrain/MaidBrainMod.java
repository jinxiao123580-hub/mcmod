package com.dsh.maidbrain;

import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(MaidBrainMod.MODID)
public class MaidBrainMod {
    public static final String MODID = "maid_brain";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public MaidBrainMod() {
        LOGGER.info("maid_brain loaded (DSH agent bridge)");
    }
}
