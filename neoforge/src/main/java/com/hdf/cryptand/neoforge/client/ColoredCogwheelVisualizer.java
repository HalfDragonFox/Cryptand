package com.hdf.cryptand.neoforge.client;

// 使用原始类型，避免泛型约束
//public class ColoredCogwheelVisualizer implements BlockEntityVisualizer {
//
//    @Override
//    public BlockEntityVisual<?> createVisual(VisualizationContext ctx, BlockEntity blockEntity, float partialTick) {
//        if (!(blockEntity instanceof KineticBlockEntity kinetic)) return null;
//        if (!(kinetic instanceof ICryptandGear colored)) return null;
//        if (!ICogWheel.isSmallCog(kinetic.getBlockState()) && !ICogWheel.isLargeCog(kinetic.getBlockState())) return null;
//        return new ColoredCogwheelVisual(ctx, kinetic, colored, partialTick);
//    }
//
//    @Override
//    public boolean skipVanillaRender(BlockEntity blockEntity) {
//        return true;
//    }
//}