package com.hdf.cryptand.neoforge.client;

//public class ClientEvents {
//
//    public static void onClientSetup(FMLClientSetupEvent event) {
//        CryptandNeoForge.WAF_LOGGER.info("Registering colored cogwheel visualizers...");
//        event.enqueueWork(() -> {
//            var simpleKineticType = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(ResourceLocation.parse("create:simple_kinetic"));
//
//            if (simpleKineticType != null) {
//                VisualizerRegistry.setVisualizer(simpleKineticType, new ColoredCogwheelVisualizer());
//            } else {
//                CryptandNeoForge.WAF_LOGGER.warn("Could not find create:simple_kinetic in BLOCK_ENTITY_TYPE registry!");
//            }
//        });
//    }
//}