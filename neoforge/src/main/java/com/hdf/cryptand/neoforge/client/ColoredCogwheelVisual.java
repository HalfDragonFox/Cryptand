package com.hdf.cryptand.neoforge.client;

//public class ColoredCogwheelVisual extends SingleAxisRotatingVisual<KineticBlockEntity> {
//
//    private final ICryptandGear colored;
//    private final boolean isLarge;
//    private final RotatingInstance additionalShaft;
//    private final Direction.Axis axis;
//
//    public ColoredCogwheelVisual(VisualizationContext context, KineticBlockEntity blockEntity, ICryptandGear colored, float partialTick) {
//        super(context, blockEntity, partialTick, Models.partial(getCogModel(blockEntity)));
//        this.colored = colored;
//        this.isLarge = ICogWheel.isLargeCog(blockEntity.getBlockState());
//        this.axis = rotationAxis();
//
//        if (isLarge) {
//            additionalShaft = instancerProvider()
//                    .instancer(com.simibubi.create.foundation.render.AllInstanceTypes.ROTATING, Models.partial(AllPartialModels.COGWHEEL_SHAFT))
//                    .createInstance();
//            additionalShaft.rotateToFace(axis)
//                    .setup(blockEntity)
//                    .setRotationOffset(com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntityRenderer.getShaftAngleOffset(axis, pos))
//                    .setPosition(getVisualPosition())
//                    .setChanged();
//        } else {
//            additionalShaft = null;
//        }
//
//        if (isLarge) {
//            applyColorTo(additionalShaft);
//        }
//        applyColorTo(rotatingModel);
//    }
//
//    private static PartialModel getCogModel(KineticBlockEntity blockEntity) {
//        boolean isLarge = ICogWheel.isLargeCog(blockEntity.getBlockState());
//        return isLarge ? AllPartialModels.SHAFTLESS_LARGE_COGWHEEL : AllPartialModels.COGWHEEL;
//    }
//
//    private void applyColorTo(RotatingInstance instance) {
//        int color = colored.getGearColor();
//        instance.setColor(new Color(color));
//        instance.setChanged();
//    }
//
//    @Override
//    public void update(float pt) {
//        super.update(pt);
//        applyColorTo(rotatingModel);
//        if (additionalShaft != null) {
//            additionalShaft.setup(blockEntity)
//                    .setRotationOffset(com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntityRenderer.getShaftAngleOffset(axis, pos))
//                    .setChanged();
//            applyColorTo(additionalShaft);
//        }
//    }
//
//    @Override
//    public void updateLight(float partialTick) {
//        super.updateLight(partialTick);
//        if (additionalShaft != null) {
//            relight(additionalShaft);
//        }
//    }
//
//    @Override
//    protected void _delete() {
//        super._delete();
//        if (additionalShaft != null) {
//            additionalShaft.delete();
//        }
//    }
//
//    @Override
//    public void collectCrumblingInstances(Consumer<Instance> consumer) {
//        super.collectCrumblingInstances(consumer);
//        if (additionalShaft != null) {
//            consumer.accept(additionalShaft);
//        }
//    }
//}
