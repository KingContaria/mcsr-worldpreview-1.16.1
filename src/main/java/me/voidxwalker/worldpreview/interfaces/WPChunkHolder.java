package me.voidxwalker.worldpreview.interfaces;

import java.util.BitSet;

public interface WPChunkHolder {

    BitSet worldpreview$getSkyLightUpdateBits();

    BitSet worldpreview$getBlockLightUpdateBits();

    void worldpreview$flushUpdates();
}
