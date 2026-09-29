/**
 * ===== OpenComputers 组件总线抽象（纯 Java，零 MC / 零 OC 依赖，2026-09-16）=====
 *
 * <p>这是「Cryptand 芯片跑进 OC 机箱」的**核心侧接口**：沙箱里的 C 固件想调 OC 组件
 * （屏幕 / 键盘 / 磁盘 / 网卡 …）时，核心把请求建模成 {@link Call}，交给平台层实现的
 * {@link ComponentBus} 去兑现；平台层在**主线程**调用真实 OC 组件后回填 {@link Result}。</p>
 *
 * <h3>分层纪律（用户 2026-09-16 定）</h3>
 * <ul>
 *   <li>本文件在 {@code common}，**绝不引用 OC 或 MC 的任何类** —— OC 的类型只在
 *       {@code neoforge/.../opencomputers} 子包的适配器里出现；</li>
 *   <li>核心与平台之间只传**本文件定义的可序列化中间类型**（String / long / double / byte[] / List / Map），
 *       平台负责与 OC 的 {@code Object[]}、{@code ImmutableItemStack} 等做双向转换；</li>
 *   <li>调用可能跨 tick：{@link #invoke} 允许阻塞（核心在自己的 worker 线程上等待，
 *       平台层在 tick 边界兑现后唤醒），与 soc 既有的"MMIO 只走消息"同构。</li>
 * </ul>
 */
package com.hdf.cryptand.soc.oc;

import java.util.List;

public interface ComponentBus {

    /**
     * 发起一次组件调用。
     *
     * @param call 调用请求（地址 / 组件名 / 方法名 / 已转换的参数）
     * @return 调用结果；**不得返回 null**，失败请返回 {@link Result#error}
     */
    Result invoke(Call call);

    /**
     * 当前可用组件的**有序表**：下标即 ABI 里的「组件句柄」（见 {@link OcAbi#REG_COMPONENT}）。
     *
     * <p>用有序表而不是 Map：C 固件用整数句柄寻址组件，必须有"句柄 → 地址/名字"的稳定映射。</p>
     */
    List<Entry> components();

    /** 组件表项：地址 + 组件名（如 {@code "1a2b…" + "screen"}） */
    record Entry(String address, String component) {
        public Entry {
            address = address == null ? "" : address;
            component = component == null ? "" : component;
        }
    }

    /**
     * 一次组件调用请求。
     *
     * @param address   组件地址（OC 的 node address；空串 = 交给平台选第一个匹配组件）
     * @param component 组件名（{@code "gpu"} / {@code "screen"} / {@code "keyboard"} / {@code "filesystem"} …）
     * @param method    方法名（{@code "set"} / {@code "getResolution"} …；核心侧先给 {@code "#<方法id>"}）
     * @param args      标量参数（只允许本文件约定的中间类型）
     * @param buffer    缓冲区参数（字符串 / 字节数组的原样字节；无则 null）。
     *                  固件把"一串字节"放在 guest 内存里，用 {@code REG_BUF_ADDR/LEN} 交地址，
     *                  核心读出来放这里 —— {@code gpu.set} 的文本、{@code gpu.blit} 的 w×h 字符阵列都走它。
     */
    record Call(String address, String component, String method, List<Object> args, byte[] buffer,
                int channel) {

        public Call {
            address = address == null ? "" : address;
            component = component == null ? "" : component;
            method = method == null ? "" : method;
            args = args == null ? List.of() : List.copyOf(args);
        }

        /**
         * 第 6 个分量 {@code channel}：邮箱通道下标。
         * {@code >= 0} 表示这次调用来自外部设备**邮箱区**（结果要写回那个通道）；
         * {@code -1} = 非邮箱来源（引导服务 / 旧的寄存器路径）。
         */

        /** 非邮箱来源（引导服务 / 旧的寄存器路径） */
        public Call(String address, String component, String method, List<Object> args, byte[] buffer) {
            this(address, component, method, args, buffer, -1);
        }

        /** 无缓冲区参数的调用 */
        public Call(String address, String component, String method, List<Object> args) {
            this(address, component, method, args, null, -1);
        }

        /** 缓冲区按 UTF-8 解成文本（无缓冲区返回空串） */
        public String bufferText() {
            return buffer == null || buffer.length == 0
                    ? "" : new String(buffer, java.nio.charset.StandardCharsets.UTF_8);
        }

        /** 中间类型里被允许的标量：判空与构造期校验都走这里 */
        public static boolean isSupportedArg(Object value) {
            return value == null
                    || value instanceof String
                    || value instanceof Boolean
                    || value instanceof Integer
                    || value instanceof Long
                    || value instanceof Double
                    || value instanceof byte[];
        }
    }

    /**
     * 一次组件调用的结果。
     *
     * @param ok     是否成功（false 时 {@link #error} 应给出原因）
     * @param values 返回值（只允许 {@link Call#isSupportedArg} 认可的中间类型）
     * @param error  失败原因（成功时空串）
     */
    record Result(boolean ok, List<Object> values, String error, int errCode) {

        public Result {
            values = values == null ? List.of() : List.copyOf(values);
            error = error == null ? "" : error;
        }

        /** 兼容旧调用：成功码 0 / 失败码 {@code ERR_COMPONENT_FAILED} */
        public Result(boolean ok, List<Object> values, String error) {
            this(ok, values, error, ok ? OcAbi.ERR_NONE : OcAbi.ERR_COMPONENT_FAILED);
        }

        public static Result ok(Object... values) {
            return new Result(true, values == null ? List.of() : List.of(values), "", OcAbi.ERR_NONE);
        }

        public static Result error(String message) {
            return new Result(false, List.of(), message == null ? "unknown" : message,
                    OcAbi.ERR_COMPONENT_FAILED);
        }

        /**
         * 带 **ABI 错误码**的失败 —— 固件必须能分清"该重试"（`ERR_NO_SPACE`）与
         * "该报错"（`ERR_NOT_FOUND`），这是 {@code cryptand-fs-design.md §3.3.3} 的硬要求；
         * 只给一句 text 是分不出来的（寄存器 ABI 里只有数）。
         */
        public static Result error(int errCode, String message) {
            return new Result(false, List.of(), message == null ? "unknown" : message, errCode);
        }

        /** 取第 index 个返回值（越界或类型不符返回 fallback）—— 供核心侧取值用 */
        public Object value(int index, Object fallback) {
            return index >= 0 && index < values.size() && values.get(index) != null
                    ? values.get(index) : fallback;
        }
    }

    /** 空实现：OC 未加载 / 未装配时使用（所有调用都以错误返回，核心据此让固件看到"无组件"） */
    ComponentBus EMPTY = new ComponentBus() {
        @Override
        public Result invoke(Call call) {
            return Result.error("no component bus");
        }

        @Override
        public List<Entry> components() {
            return List.of();
        }
    };
}
