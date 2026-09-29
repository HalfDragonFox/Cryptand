/*
 * ============================================================================
 * Cryptand RV32 native kernel — JNI 桥（jni_bridge.cpp，2026-09-17）
 *
 * Java 侧对应类：com.hdf.cryptand.soc.nativebridge.NativeRv32
 *   （common 模块，纯 Java 零 MC 依赖 ⇒ 与 soc 既有分层一致）
 *
 * 约定：
 *   · handle 是 native Machine 指针，Java 持有 long；create/destroy 成对。
 *   · 解释循环里没有 JNI 往返；只有"设备访问"会以 MMIO 事务的形式浮上来
 *     （step 提前返回 → Java 在设备上兑现 → completeMmio 继续）。
 *   · 所有数组都用 GetPrimitiveArrayCritical 之外的安全路径（数组小、频率低）。
 * ============================================================================
 */

#include <jni.h>
#include <cstring>

#include "rv32.h"
#include "sandbox.h"

using cryptand::Config;
using cryptand::Machine;

namespace {

Machine* machine(jlong handle)
{
    return reinterpret_cast<Machine*>(handle);
}

Config configOf(jint resetVector, jint ramBase, jint ramSize, jint romBase, jint romSize)
{
    Config cfg;
    cfg.resetVector = (uint32_t)resetVector;
    cfg.ramBase = (uint32_t)ramBase;
    cfg.ramSize = (uint32_t)ramSize;
    cfg.romBase = (uint32_t)romBase;
    cfg.romSize = (uint32_t)romSize;
    return cfg;
}

} /* namespace */

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_create(
        JNIEnv*, jclass, jint resetVector, jint ramBase, jint ramSize, jint romBase, jint romSize)
{
    auto* m = new Machine(configOf(resetVector, ramBase, ramSize, romBase, romSize));
    return reinterpret_cast<jlong>(m);
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_destroy(JNIEnv*, jclass, jlong handle)
{
    delete machine(handle);
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_reset(JNIEnv*, jclass, jlong handle)
{
    if (machine(handle)) {
        machine(handle)->reset();
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_loadImage(
        JNIEnv* env, jclass, jlong handle, jint addr, jbyteArray data)
{
    Machine* m = machine(handle);
    if (!m || !data) {
        return;
    }
    const jsize len = env->GetArrayLength(data);
    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    if (buf) {
        m->loadImage((uint32_t)addr, reinterpret_cast<const uint8_t*>(buf), (uint32_t)len);
        env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    }
}

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_step(JNIEnv*, jclass, jlong handle, jint cycles)
{
    Machine* m = machine(handle);
    return m ? (jint)m->step((int)cycles) : 0;
}

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_pc(JNIEnv*, jclass, jlong handle)
{
    Machine* m = machine(handle);
    return m ? (jint)m->pc() : 0;
}

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_reg(JNIEnv*, jclass, jlong handle, jint index)
{
    Machine* m = machine(handle);
    return m ? (jint)m->reg((int)index) : 0;
}

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_csr(JNIEnv*, jclass, jlong handle, jint addr)
{
    Machine* m = machine(handle);
    return m ? (jint)m->csr((uint32_t)addr) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_instructionsRetired(JNIEnv*, jclass, jlong handle)
{
    Machine* m = machine(handle);
    return m ? (jlong)m->instructionsRetired() : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_faulted(JNIEnv*, jclass, jlong handle)
{
    Machine* m = machine(handle);
    return (m && m->faulted()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_halted(JNIEnv*, jclass, jlong handle)
{
    Machine* m = machine(handle);
    return (m && m->halted()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_faultCause(JNIEnv*, jclass, jlong handle)
{
    Machine* m = machine(handle);
    return m ? (jint)m->faultCause() : 0;
}

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_faultTval(JNIEnv*, jclass, jlong handle)
{
    Machine* m = machine(handle);
    return m ? (jint)m->faultTval() : 0;
}

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_faultEpc(JNIEnv*, jclass, jlong handle)
{
    Machine* m = machine(handle);
    return m ? (jint)m->faultEpc() : 0;
}

/* ---------- MMIO 事务往返 ---------- */

JNIEXPORT jboolean JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_hasMmio(JNIEnv*, jclass, jlong handle)
{
    Machine* m = machine(handle);
    return (m && m->hasMmio()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_mmioAddr(JNIEnv*, jclass, jlong handle)
{
    Machine* m = machine(handle);
    return m ? (jint)m->mmio().addr : 0;
}

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_mmioSize(JNIEnv*, jclass, jlong handle)
{
    Machine* m = machine(handle);
    return m ? (jint)m->mmio().size : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_mmioIsWrite(JNIEnv*, jclass, jlong handle)
{
    Machine* m = machine(handle);
    return (m && m->mmio().isWrite) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_mmioValue(JNIEnv*, jclass, jlong handle)
{
    Machine* m = machine(handle);
    return m ? (jint)m->mmio().value : 0;
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_completeMmio(JNIEnv*, jclass, jlong handle, jint value)
{
    Machine* m = machine(handle);
    if (m) {
        m->completeMmio((uint32_t)value);
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_setPendingInterrupts(
        JNIEnv*, jclass, jlong handle, jint bits)
{
    Machine* m = machine(handle);
    if (m) {
        m->setPendingInterrupts((uint32_t)bits);
    }
}

/* ---------- 内存（诊断/自测） ---------- */

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_readMemory(
        JNIEnv* env, jclass, jlong handle, jint addr, jbyteArray out)
{
    Machine* m = machine(handle);
    if (!m || !out) {
        return;
    }
    const jsize len = env->GetArrayLength(out);
    jbyte* buf = env->GetByteArrayElements(out, nullptr);
    if (buf) {
        m->readMemory((uint32_t)addr, reinterpret_cast<uint8_t*>(buf), (uint32_t)len);
        env->ReleaseByteArrayElements(out, buf, 0);
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeRv32_writeMemory(
        JNIEnv* env, jclass, jlong handle, jint addr, jbyteArray in)
{
    Machine* m = machine(handle);
    if (!m || !in) {
        return;
    }
    const jsize len = env->GetArrayLength(in);
    jbyte* buf = env->GetByteArrayElements(in, nullptr);
    if (buf) {
        m->writeMemory((uint32_t)addr, reinterpret_cast<const uint8_t*>(buf), (uint32_t)len);
        env->ReleaseByteArrayElements(in, buf, JNI_ABORT);
    }
}

/* ==========================================================================
 * 沙箱（自驱动虚拟机）—— Java 侧对应 NativeSandbox
 *
 * 与上面的同步 Machine 不同：沙箱带内部时钟与消息队列，Java 只需
 *   ① 用 setClockHz 配置一次"1 秒执行多少周期"
 *   ② 在自己的线程里调 run()（阻塞，直到 stop()）
 *   ③ 周期性 poll() 取消息、snapshot() 读状态、mmioResult() 兑现设备访问
 * ========================================================================== */

namespace {

cryptand::Sandbox* sandbox(jlong handle)
{
    return reinterpret_cast<cryptand::Sandbox*>(handle);
}

cryptand::Config sandboxConfig(jint resetVector, jint ramBase, jint ramSize, jint romBase, jint romSize)
{
    return configOf(resetVector, ramBase, ramSize, romBase, romSize);
}

} /* namespace */

JNIEXPORT jlong JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_create(
        JNIEnv*, jclass, jint resetVector, jint ramBase, jint ramSize, jint romBase, jint romSize)
{
    auto* s = new cryptand::Sandbox(sandboxConfig(resetVector, ramBase, ramSize, romBase, romSize));
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_destroy(JNIEnv*, jclass, jlong handle)
{
    delete sandbox(handle);
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_setClockHz(JNIEnv*, jclass, jlong handle, jlong hz)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->setClockHz((uint64_t)hz);
    }
}

JNIEXPORT jlong JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_clockHz(JNIEnv*, jclass, jlong handle)
{
    cryptand::Sandbox* s = sandbox(handle);
    return s ? (jlong)s->clockHz() : 0;
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_setQuantum(JNIEnv*, jclass, jlong handle, jint cycles)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->setQuantum((int)cycles);
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_loadImage(
        JNIEnv* env, jclass, jlong handle, jint addr, jbyteArray in)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (!s || !in) {
        return;
    }
    const jsize len = env->GetArrayLength(in);
    jbyte* buf = env->GetByteArrayElements(in, nullptr);
    if (buf) {
        s->cmdLoadImage((uint32_t)addr, reinterpret_cast<const uint8_t*>(buf), (uint32_t)len);
        env->ReleaseByteArrayElements(in, buf, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_reset(JNIEnv*, jclass, jlong handle)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->cmdReset();
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_run(JNIEnv*, jclass, jlong handle)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->run();
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_stop(JNIEnv*, jclass, jlong handle)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->stop();
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_pause(JNIEnv*, jclass, jlong handle)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->cmdPause();
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_resume(JNIEnv*, jclass, jlong handle)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->cmdRun();
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_step(JNIEnv*, jclass, jlong handle, jint cycles)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->cmdStep((int)cycles);
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_setBreakpoint(JNIEnv*, jclass, jlong handle, jint addr)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->cmdSetBreakpoint((uint32_t)addr);
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_clearBreakpoint(JNIEnv*, jclass, jlong handle, jint addr)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->cmdClearBreakpoint((uint32_t)addr);
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_clearBreakpoints(JNIEnv*, jclass, jlong handle)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->cmdClearBreakpoints();
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_writeReg(JNIEnv*, jclass, jlong handle, jint index, jint value)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->cmdWriteReg((int)index, (uint32_t)value);
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_writeMem(
        JNIEnv* env, jclass, jlong handle, jint addr, jbyteArray in)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (!s || !in) {
        return;
    }
    const jsize len = env->GetArrayLength(in);
    jbyte* buf = env->GetByteArrayElements(in, nullptr);
    if (buf) {
        s->cmdWriteMem((uint32_t)addr, reinterpret_cast<const uint8_t*>(buf), (uint32_t)len);
        env->ReleaseByteArrayElements(in, buf, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_setInterrupts(JNIEnv*, jclass, jlong handle, jint bits)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->cmdSetInterrupts((uint32_t)bits);
    }
}

JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_mmioResult(JNIEnv*, jclass, jlong handle, jint value)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->cmdMmioResult((uint32_t)value);
    }
}

/* poll(handle, out[4]) -> 1 = 有消息 */
JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_poll(JNIEnv* env, jclass, jlong handle, jintArray out)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (!s || !out || env->GetArrayLength(out) < 4) {
        return 0;
    }
    cryptand::Sandbox::Message m;
    if (s->poll(&m) == 0) {
        return 0;
    }
    const jint v[4] = {(jint)m.type, (jint)m.a, (jint)m.b, (jint)m.c};
    env->SetIntArrayRegion(out, 0, 4, v);
    return 1;
}

/*
 * waitMessage(handle, out[5], timeoutMs) -> 1 = 有消息, 0 = 超时, -1 = 已 stop
 *
 * 这是"减少 JNI 调用"的第一处：Java 消息泵不再每秒轮询 1000 次 poll，
 * 而是阻塞在这里等消息入队（native 侧 pushLocked 会唤醒）。
 * out = [type, a, b, c, instret] —— instret 随消息一起带回，Java 侧处理设备访问
 * （MSG_MMIO）时用它把设备时间推进到"沙箱当前周期"，**不需要**额外查询。
 */
JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_waitMessage(
        JNIEnv* env, jclass, jlong handle, jlongArray out, jint timeoutMs)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (!s) {
        return -1;
    }
    cryptand::Sandbox::Message m;
    const int r = s->waitMessage(&m, (int)timeoutMs);
    if (r == 1 && out != nullptr && env->GetArrayLength(out) >= 5) {
        const jlong v[5] = {(jlong)m.type, (jlong)m.a, (jlong)m.b, (jlong)m.c,
                            (jlong)s->instructionsRetired()};
        env->SetLongArrayRegion(out, 0, 5, v);
    }
    return r;
}

/*
 * heartbeat(handle, irqBits, quantum, out[12]) —— 每 tick 一次，一次调用办两件事：
 * 下发宿主侧设备中断位图 / 取回沙箱状态。out = [pc, instret, running,
 * waitingMmio, faulted, halted, pendingMessages, faultCause, faultTval, faultEpc,
 * idlePaused, cyclesThisSecond]。
 * quantum <= 0 表示不改（配置一次即可）。
 * ⚠ 心跳同时是**存活信号**：宿主卡顿不影响虚拟机，但心跳断了超过看门狗阈值它会自己暂停。
 */
JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_heartbeat(
        JNIEnv* env, jclass, jlong handle, jint irqBits, jint quantum, jlongArray out)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (!s) {
        return;
    }
    cryptand::Sandbox::Heartbeat hb;
    s->heartbeat((uint32_t)irqBits, (int)quantum, &hb);
    if (out == nullptr || env->GetArrayLength(out) < 12) {
        return;
    }
    const jlong v[12] = {
        (jlong)hb.pc,
        (jlong)hb.instret,
        (jlong)hb.running,
        (jlong)hb.waitingMmio,
        (jlong)hb.faulted,
        (jlong)hb.halted,
        (jlong)hb.pendingMessages,
        (jlong)hb.faultCause,
        (jlong)hb.faultTval,
        (jlong)hb.faultEpc,
        (jlong)hb.idlePaused,
        (jlong)hb.cyclesThisSecond,
    };
    env->SetLongArrayRegion(out, 0, 12, v);
}

/*
 * directMemory(handle, addr, len) -> DirectByteBuffer（零拷贝直通 guest 内存）
 *
 * 绕开 readMemory 的 GetByteArrayElements + memcpy；只对整段落在 ROM/RAM 内的请求有效，
 * 其余返回 null（调用方回落到 readMemory）。⚠ 返回的是**共享**内存，不是快照。
 */
JNIEXPORT jobject JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_directMemory(
        JNIEnv* env, jclass, jlong handle, jint addr, jint len)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (!s || len <= 0) {
        return nullptr;
    }
    uint8_t* p = s->memoryPointer((uint32_t)addr, (uint32_t)len);
    if (p == nullptr) {
        return nullptr;
    }
    return env->NewDirectByteBuffer(p, (jlong)len);
}

/* snapshot(handle, out[44]) —— 布局见 NativeSandbox 注释 */
JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_snapshot(JNIEnv* env, jclass, jlong handle, jlongArray out)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (!s || !out || env->GetArrayLength(out) < 44) {
        return;
    }
    cryptand::Sandbox::Snapshot snap;
    s->snapshot(&snap);

    jlong v[44];
    v[0] = (jlong)snap.pc;
    /* 布局（见 NativeSandbox 注释）：[1] = x1 … [31] = x31，[32] 保留给 x0。
     * 注意 regs_[0] 是 x0，必须跳过 —— 否则每个寄存器都会读到"前一个"的值。 */
    for (int i = 1; i < 32; i++) {
        v[i] = (jlong)snap.regs[i];
    }
    v[32] = 0;
    v[33] = (jlong)snap.instret;
    v[34] = (jlong)snap.clockHz;
    v[35] = (jlong)snap.running;
    v[36] = (jlong)snap.waitingMmio;
    v[37] = (jlong)snap.faulted;
    v[38] = (jlong)snap.halted;
    v[39] = (jlong)snap.breakpointCount;
    v[40] = (jlong)snap.faultCause;
    v[41] = (jlong)snap.faultTval;
    v[42] = (jlong)snap.faultEpc;
    v[43] = (jlong)snap.pendingMessages;
    env->SetLongArrayRegion(out, 0, 44, v);
}

/* setPauseOnSilence(handle, ms) —— 心跳断了多久就暂停（0 = 关闭看门狗；默认 10000） */
JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_setPauseOnSilence(JNIEnv*, jclass, jlong handle, jint ms)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (s) {
        s->setIdlePauseMs(ms < 0 ? 0u : (uint32_t)ms);
    }
}

/* clint(handle, out[5]) —— [mtime, mtimecmp, enabled, pending, irqCount]（宿主只读诊断） */
JNIEXPORT void JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_clint(JNIEnv* env, jclass, jlong handle, jlongArray out)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (!s || !out || env->GetArrayLength(out) < 5) {
        return;
    }
    cryptand::Sandbox::ClintState st{};
    s->clint(&st);
    jlong v[5];
    v[0] = (jlong)st.mtime;
    v[1] = (jlong)st.mtimecmp;
    v[2] = (jlong)st.enabled;
    v[3] = (jlong)st.pending;
    v[4] = (jlong)st.irqCount;
    env->SetLongArrayRegion(out, 0, 5, v);
}

JNIEXPORT jint JNICALL
Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox_readMemory(
        JNIEnv* env, jclass, jlong handle, jint addr, jbyteArray out, jint len)
{
    cryptand::Sandbox* s = sandbox(handle);
    if (!s || !out || len <= 0) {
        return 0;
    }
    const jsize cap = env->GetArrayLength(out);
    const jsize n = len < cap ? len : cap;
    jbyte* buf = env->GetByteArrayElements(out, nullptr);
    if (!buf) {
        return 0;
    }
    const int got = s->readMemory((uint32_t)addr, reinterpret_cast<uint8_t*>(buf), (uint32_t)n);
    env->ReleaseByteArrayElements(out, buf, 0);
    return (jint)got;
}

} /* extern "C" */
