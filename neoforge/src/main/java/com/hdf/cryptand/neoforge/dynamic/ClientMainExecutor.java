package com.hdf.cryptand.neoforge.dynamic;

import net.minecraft.client.Minecraft;

import java.util.function.Consumer;

/**
 * 客户端主线程派发（**唯一**引用 {@code net.minecraft.client.*} 的动态框架类）。
 *
 * <p>单独成类的原因（低侵入约束）：{@link CryptandDynamicScheduler} 本身不引用 MC 客户端类型，
 * 服务端路径加载它不会崩；只有真正要往客户端主线程派发时才碰这个类。</p>
 */
public final class ClientMainExecutor {

    private ClientMainExecutor() {
    }

    /** 返回一个把任务投递到客户端主线程的执行器。 */
    public static Consumer<Runnable> mc() {
        return task -> {
            final Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(task);
            } else {
                task.run();
            }
        };
    }
}
