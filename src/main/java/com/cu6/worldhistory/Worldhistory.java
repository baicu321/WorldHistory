package com.cu6.worldhistory;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Worldhistory.MODID)
public class Worldhistory {

    public static final String MODID = "worldhistory";

    private static final Logger LOGGER = LogUtils.getLogger();

    public Worldhistory(IEventBus modEventBus, ModContainer modContainer) {

    }

}
