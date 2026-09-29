package com.hdf.cryptand.neoforge.powergrid.element;

import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.List;

/**
 * ===== 元件映射器注册表（有序分发）=====
 *
 * <p>顺序即优先级，与原 {@code addElement} 的 {@code if/else if} 链一一对应；
 * 末位 {@link AssemblerDeviceMapper} 是兜底（覆盖所有 PowerGrid/原版设备）。
 */
public final class ElementMappers {

    private ElementMappers() {}

    /** 有序映射器表（顺序敏感：绕组在主方块判定之前，兜底永远最后）。 */
    private static final List<ElementMapper> ORDERED = List.of(
            new AcSourceMapper(),
            new CapacitorMapper(),
            new InductorMapper(),
            new ResistorMapper(),
            new WindingMapper(),
            new AssemblerDeviceMapper());

    /** 分发：第一个 {@code supports(be)} 为真的映射器负责本次装配。 */
    public static void dispatch(ElementMapContext c, BlockEntity be) {
        if (be == null) return;
        for (ElementMapper m : ORDERED) {
            if (m.supports(be)) {
                m.apply(c, be);
                return;
            }
        }
    }

    /** 映射器清单（诊断用）。 */
    static List<String> names() {
        return ORDERED.stream().map(ElementMapper::name).toList();
    }
}
