package com.soog.sack_inspector;

import net.fabricmc.api.ModInitializer;

public class Sack_inspector implements ModInitializer {

    @Override
    public void onInitialize() {
        ChunkChecker.init();
    }
}
