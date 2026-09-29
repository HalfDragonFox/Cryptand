package com.hdf.cryptand.neoforge.truescreen;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;

/**
 * ===== 真彩屏移植件的注册入口（Java 侧可见的 SPI 接口，2026-09-27 任务 D）=====
 *
 * <p><b>为什么需要这个接口（不是"多余的一层"）</b>：移植件全部在 sourceSet
 * <b>scala</b>（{@code neoforge/src/main/scala/...}），而它的集成点是
 * {@code soc/content/SocContent.java}（sourceSet <b>java</b>）。两个源集在 Gradle 里是
 * <b>分开编译</b>的（{@code compileJava} 先跑，类路径里没有 {@code build/classes/scala/main}）
 * ⇒ ｛@code src/main/java} 里的代码**在编译期看不到** {@code TrueScreenContent}，
 * 唯一的桥就是运行期发现（{@code Class.forName} / SPI / 注解扫描）。</p>
 *
 * <p>本仓的纪律不允许 {@code @EventBusSubscriber}（自动注册会绕过子包开关，见
 * 2026-08-30 的根因记录），所以这里用 JDK 标准 SPI：</p>
 * <ul>
 *   <li>本接口在 <b>java</b> 源集（{@code SocContent} 能直接编译引用）；</li>
 *   <li>实现 {@code TrueScreenRegistrarImpl} 放在 <b>scala</b> 源集里、且写成 <b>Scala class</b>
 *       （Scala class 才既有 public 无参构造器供 ServiceLoader instantiate，又能直接引用
 *       {@code TrueScreenContent}；写成 src/main/scala 下的 Java 类会因"javac 看不到同轮 Scala 类"
 *       而编译失败，本轮实测），并由 {@code META-INF/services/...TrueScreenRegistrar} 声明；</li>
 *   <li>{@code SocContent} 用 {@link java.util.ServiceLoader} 实例化它并调用
 *       {@link #install(IEventBus)} —— 时机仍在 mod 构造期（OC 驱动表上锁之前）。</li>
 * </ul>
 *
 * <p>⚠ 只有一个实现，且只有一个调用点：{@code SocContent.register → registerTrueScreenContent}。
 * 别再在别处注册屏幕条目。</p>
 */
public interface TrueScreenRegistrar {

    /**
     * 注册全部屏幕条目（方块 / 物品 / BE 类型 / DataComponent / OC capability / 驱动 / 报文通道 /
     * 客户端三条注册线）。失败由调用方兜底记录（不影响启动）。
     */
    void install(IEventBus modBus);

    /**
     * 该方块实体是不是真彩屏（含非原点块）。<b>由 scala 侧实现</b>（{@code TrueScreenRegistrarImpl}）。
     *
     * <p>为什么要有这个方法：{@code src/main/java} <b>不能</b>直接 {@code instanceof} scala 源集的类
     * （两个源集编译期互不可见，唯一的桥就是本接口 —— 见类注释），所以判定统一从这里走。</p>
     */
    default boolean isTrueScreen(BlockEntity be) {
        return false;
    }

    /** 该方块实体是不是真彩屏<b>主块</b>（多块结构原点）。由 scala 侧实现。 */
    default boolean isTrueScreenOrigin(BlockEntity be) {
        return false;
    }
}
