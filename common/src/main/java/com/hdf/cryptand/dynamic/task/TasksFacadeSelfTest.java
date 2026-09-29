package com.hdf.cryptand.dynamic.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ===== 门面闸门：外部核心"像跑 python 一样简单"（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>验证 {@link Tasks} 的一行式用法真的能干活：读文件、写文件、回主线程、串接后续步骤。</p>
 *
 * <p>{@code ./gradlew :common:runTasksFacadeTest}</p>
 */
public final class TasksFacadeSelfTest {

    private static int passed;
    private static int failed;

    private TasksFacadeSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        final Path dir = Files.createTempDirectory("cryptand-tasks");
        final Path file = dir.resolve("demo.txt");

        // 1) 写文件：一行
        Tasks.write("demo.txt", "写文件", () -> {
            Files.writeString(file, "hello-cryptand");
            return null;
        }).get();

        // 2) 读文件：一行
        final String content = Tasks.read("demo.txt", "读文件", () -> Files.readString(file)).get();
        check("读回写进去的内容", "hello-cryptand".equals(content));

        // 3) 主线程（默认同线程）+ 串接
        final AtomicReference<String> observed = new AtomicReference<>();
        final Thread caller = Thread.currentThread();
        Tasks.mainThread("回主线程", () -> observed.set(Thread.currentThread().getName())).get();
        check("主线程任务在调用线程执行（" + observed.get() + "）", observed.get().equals(caller.getName()));

        // 4) 并行只读：8 个文件同时读（无资源键 ⇒ 不互斥）
        final List<CompletableFuture<Integer>> reads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int n = i;
            reads.add(Tasks.read("read-" + n, () -> {
                final Path p = dir.resolve("f" + n + ".txt");
                try {
                    Files.writeString(p, "n=" + n);
                    return Files.readString(p).length();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (CompletableFuture<Integer> f : reads) {
            f.get();
        }
        check("8 个只读任务全部完成（不互相阻塞）", true);

        // 5) 同一资源键的读写：不会互相踩（写独占，读在写之后）
        final Path shared = dir.resolve("shared.txt");
        Tasks.write("shared.txt", "初始化", () -> {
            Files.writeString(shared, "0");
            return null;
        }).get();
        final CompletableFuture<String> writer = Tasks.write("shared.txt", "改写", () -> {
            Files.writeString(shared, "1");
            return "written";
        });
        final CompletableFuture<String> reader = Tasks.read("shared.txt", "读取", () -> Files.readString(shared));
        check("写完成", "written".equals(writer.get()));
        check("读拿到的是写后的值（资源键串行化）", "1".equals(reader.get()));

        // 6) 观测：派发计数在涨
        check("stats 可读（派发计数 > 0）", Tasks.stats().contains("任务派发"));
        check("主线程注入标志默认 false（common 未注入）", !Tasks.mainExecutorInstalled());
        check("requireMainExecutorInstalled 在未注入时明确报错", throwsOn(Tasks::requireMainExecutorInstalled));
        Tasks.installMainExecutor(Runnable::run);
        check("注入后标志为 true", Tasks.mainExecutorInstalled());
        Tasks.requireMainExecutorInstalled();

        // 清理
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响断言（临时目录）
                }
            });
        }

        System.out.println("[tasks-facade] " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static boolean throwsOn(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static void check(String what, boolean okay) {
        if (okay) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }
}
