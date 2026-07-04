package com.hdf.cryptand.fabric;

import com.hdf.cryptand.Cryptand;
import net.fabricmc.api.ModInitializer;

public class CryptandFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Cryptand.init();
    }
}
