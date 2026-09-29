package com.hdf.cryptand.soc.board;

/**
 * ===== 主线程 → 虚拟机 的统一消息缓存区（纯 Java，零 MC，2026-09-26 用户定案）=====
 *
 * <p>用户原话："虚拟机和主线程定义一个缓存区，用于消息等接收，方便使用" +
 * "主线程这边比如键盘发送消息到虚拟机的话，可以持续发送直到虚拟机接收消息上限，
 * 然后虚拟机一次性消费"。</p>
 *
 * <h3>一份实现，两个视角</h3>
 * <p>本类是这条通道的<b>唯一实现</b>，同时提供两个视角，二者是同一份状态，不是两套代码：</p>
 * <ul>
 *   <li><b>宿主视角</b>（{@link #append} / {@link #takeAll} / {@link #pendingBytes} /
 *       {@link #dropped}）：主线程只管往这里灌，灌到上限就 {@link #dropped} 计数，绝不静默丢；</li>
 *   <li><b>guest 视角</b>（{@link #guestHeaderA} / {@link #guestHeaderB} / {@link #window} /
 *       {@link #windowOffset} / {@link #syncGuestTail}）：把同一份状态编码成 guest RAM 里那段
 *       <b>32 字节头 + {@link #MESSAGE_BYTES} 环形数据区</b>的字节。</li>
 * </ul>
 * <p>⚠ 纪律：平台层（neoforge）<b>只允许</b>按这里给出的常量偏移写内存 —— 任何"在平台层另写一套偏移"
 * 都会立刻变成两份布局，而"沙盒里跑得好、真机上错位"正是这么来的。固件侧的 C 镜像
 * （{@code excode/firmware/common/hal.h} 的 {@code HAL_MSG_OFF_*}）由离线闸门与这里逐项对拍。</p>
 *
 * <h3>guest 内存布局（本文件是唯一来源）</h3>
 * <pre>
 *   +0x00 magic    u32  'CMSG'（宿主写；固件读到它才知道窗口已就绪）
 *   +0x04 head     u32  **单调写计数**（宿主独占；环形下标 = head % capacity）
 *   +0x08 tail     u32  **单调读计数**（**guest 独占**：固件消费一条就把它推进 len+2）
 *   +0x0C used     u32  待发字节数 = head - tail（宿主写；也就是"看容量"里的已用）
 *   +0x10 capacity u32  环形数据区容量（宿主写；固件按它算下标，不硬编码）
 *   +0x14 dropped  u32  因满被丢弃的消息<b>条数</b>（宿主写）
 *   +0x18 state    u32  状态位：READY / FULL / DROPPED
 *   +0x1C seq      u32  累计入队消息条数（单调，固件用它一眼看出"有没有新消息"）
 *   +0x20 起 = 环形数据区 capacity 字节（帧 = kind 1B + len 1B + 数据 len B）
 * </pre>
 *
 * <h3>为什么 head/tail 是"单调计数"而不是"环形下标"</h3>
 * <p>用单调计数（环形下标 = 计数 % 容量）时 {@code head == tail} <b>当且仅当</b>队列为空，
 * 于是"刚好填满"（used == capacity）与"空"不会撞在一起 —— 若用下标，满与空同为 head == tail，
 * 固件会在大塞满时把满队列读成空队列并<b>永久卡住</b>（宿主已满、写不进去、guest 不消费）。
 * 代价只是固件侧取一条消息时做两次取模，而这是输入路径、与性能无关。</p>
 *
 * <h3>单写者纪律（谁写队列 / 谁写镜像）</h3>
 * <p><b>队列</b>（本对象：环形数据 + head/dropped/… 这些宿主侧字段）——<b>任何线程都可以写</b>，
 * 但只能经 {@link #append} / {@link #appendBytes} 这两个入口，且本类的状态方法**全部内部加锁**
 * （服务端 tick、OC 组件回调、MCP 工具、子代理脚本都会往这里追加；见 2026-09-27 的并发断言）。</p>
 * <p><b>guest 内存里的那段镜像</b>——<b>只有 tick pump 那一个线程写</b>（平台层
 * {@code CryptandOcArchitecture#pumpMessageQueue}）：它用 {@link #snapshotForPublish()}
 * 在锁内一次取齐"数据窗口 + 写入偏移 + A/B 段头"，再到锁外写 guest RAM。这样"数据与 head
 * 一定配套"（不会出现 head 已经前进、而对应的数据字节还没写进 guest 的那种撕裂视图）。</p>
 * <p>guest 侧依旧只写 {@code tail} 一个字段 ⇒ <b>每个字段仍然只有一个写者</b>；
 * 锁只解决宿主侧多个线程同时进队列的问题，不是"用大锁糊住并发"。</p>
 * <p>⚠ 队列写入者必须**只追加、不发布**：任何绕过 {@link #snapshotForPublish()} 直接往
 * guest 那段内存写字节的代码都是第二个镜像写者，必须改成入队。</p>
 *
 * <h3>与"邮箱区"的分工</h3>
 * <p>邮箱区（{@code OcAbi} 那一套）是 <b>虚拟机 → 主线程</b> 的组件调用通道，已在跑；
 * 本类是反向的 <b>主线程 → 虚拟机</b> 通道（键盘 / 串口 / 宿主通知 / 外部事件都走它）。
 * 两者方向相反、职责对称，都落在 guest RAM 里（零 MMIO 往返）—— 固件侧只认"读这段内存"。</p>
 */
public final class HostMessageRing {

    /** 消息种类：固件按 kind 分发（UART 字节流 / 键盘 / 外部事件 / 宿主通知） */
    public static final int KIND_SERIAL = 1;
    public static final int KIND_KEY = 2;
    public static final int KIND_EVENT = 3;
    public static final int KIND_NOTICE = 4;

    /** 环形数据区容量（字节）—— guest 内存里那段的大小（不含头） */
    public static final int MESSAGE_BYTES = 4096;

    /** 头部字节数（状态/头尾指针/容量/丢弃计数/kind） */
    public static final int HEADER_BYTES = 32;

    /** 一条消息 = 1 字节 kind + 1 字节长度（<=255）+ 数据 */
    public static final int MAX_PAYLOAD = 255;

    // ==================== guest 内存布局常量（唯一来源；改这里就要同步改 hal.h） ====================

    /** 头部魔数：小端写出去就是 'C''M''S''G'（固件按它判断"窗口已就绪"） */
    public static final int MAGIC = 0x47534D43;

    public static final int OFF_MAGIC = 0x00;
    /** 单调写计数（宿主独占） */
    public static final int OFF_HEAD = 0x04;
    /** 单调读计数（**guest 独占**：固件消费后写回） */
    public static final int OFF_TAIL = 0x08;
    /** 待发字节数 = head - tail（宿主写；固件用它"看容量"） */
    public static final int OFF_USED = 0x0C;
    public static final int OFF_CAPACITY = 0x10;
    /** 因满丢弃的消息条数（宿主写；固件用它"看丢弃"） */
    public static final int OFF_DROPPED = 0x14;
    public static final int OFF_STATE = 0x18;
    /** 累计入队消息条数（单调） */
    public static final int OFF_SEQ = 0x1C;

    /** 状态位：窗口已就绪（magic 有效） */
    public static final int STATE_READY = 0x1;
    /** 状态位：此刻一个字节都放不下（used == capacity） */
    public static final int STATE_FULL = 0x2;
    /** 状态位：曾经因满丢弃过（dropped > 0） */
    public static final int STATE_DROPPED = 0x4;

    /**
     * 宿主独占的头部区间：A = magic + head，B = used..seq。
     *
     * <p>分成两段的唯一原因是 {@link #OFF_TAIL} 夹在中间，而 <b>tail 归 guest 独占</b> ——
     * 宿主若把整个 32 字节头一次性写下去，就会把 guest 刚推进的 tail <b>倒回去</b>，
     * guest 于是把已经消费过的帧再读一遍（真机上表现为"命令被执行两次"）。</p>
     */
    public static final int HOST_A_OFFSET = OFF_MAGIC;
    public static final int HOST_A_BYTES = OFF_TAIL - OFF_MAGIC;              // 8
    public static final int HOST_B_OFFSET = OFF_USED;
    public static final int HOST_B_BYTES = HEADER_BYTES - OFF_USED;           // 20

    // ==================== 状态 ====================

    private final int capacity;
    /** 宿主侧镜像（与 guest 数据区逐字节同构） */
    private final byte[] ring;
    private int head;
    private int tail;
    private long dropped;
    private long appended;
    private long consumed;
    /** guest 写回的 tail 不自洽的次数（诊断；正常恒为 0） */
    private long syncAnomalies;
    /** 宿主侧有变更、还没发布进 guest RAM（初始 true：开机第一次发布要把 magic 立起来） */
    private boolean dirty = true;

    public HostMessageRing() {
        this(MESSAGE_BYTES);
    }

    public HostMessageRing(int capacityBytes) {
        // 最小容量取 2 = 一条"空消息"的帧长（kind + len）：再小就没有任何消息放得下，
        // 那属于参数错误而不是"小队列"（自测里专门用 3 字节验证"刚好填满"这条边界）。
        this.capacity = Math.max(2, capacityBytes);
        this.ring = new byte[this.capacity];
    }

    // ==================== 宿主视角（发送侧） ====================

    /**
     * 追加一条消息（**任何线程可调**：服务端 tick / OC 组件回调 / MCP 工具；**不等待虚拟机**）。
     *
     * <p>这是队列的**唯一**写入入口，内部加锁（见类说明的"谁写队列 / 谁写镜像"）。</p>
     *
     * @return 是否真的入队（false = 到上限被丢弃，调用方应当把 {@link #dropped()} 报出来）
     */
    public synchronized boolean append(int kind, byte[] payload) {
        final int len = payload == null ? 0 : Math.min(payload.length, MAX_PAYLOAD);
        final int frameLen = len + 2;
        if (used() + frameLen > capacity) {
            dropped++;
            dirty = true;               // 丢弃计数也要发布出去（固件要能"看丢弃"）
            return false;
        }
        int at = index(head);
        ring[at] = (byte) (kind & 0xFF);
        if (++at >= capacity) {
            at = 0;
        }
        ring[at] = (byte) len;
        for (int i = 0; i < len; i++) {
            if (++at >= capacity) {
                at = 0;
            }
            ring[at] = payload[i];
        }
        head += frameLen;
        appended++;
        dirty = true;
        return true;
    }

    /** 批量追加（如一段串口数据）；与 {@link #append} 同一个入口语义 */
    public synchronized int appendBytes(int kind, byte[] data, int offset, int length) {
        int ok = 0;
        for (int i = 0; i < length; i++) {
            if (!append(kind, new byte[]{data[offset + i]})) {
                break;                      // 到上限：剩下的由调用方处理（并看到 dropped）
            }
            ok++;
        }
        return ok;
    }

    /**
     * **一次性取空**（宿主侧视角：把当前全部消息拼成一段帧字节交出去）。
     *
     * <p>⚠ 生产路径上取空的是 <b>guest</b>（固件按 tail 读 guest 内存，见 {@code hal_msg_take}），
     * 本方法服务于离线装置/直连沙箱（给"一次取空"这条语义一个可断言的口）。
     * 它同样推进 tail 并标脏 ⇒ 下一次发布就把"空"告诉 guest。</p>
     */
    public synchronized byte[] takeAll() {
        final int used = used();
        if (used == 0) {
            return EMPTY;
        }
        final byte[] out = window();
        tail = head;
        consumed += used;
        dirty = true;
        return out;
    }

    /** 待发字节数（诊断；上限判断也用它） */
    public synchronized int pendingBytes() {
        return used();
    }

    /** 待发消息条数（按帧扫一遍；只给诊断与自测用） */
    public synchronized int pendingMessages() {
        int pos = tail;
        int n = 0;
        while ((head - pos) > 0 && n <= capacity) {
            pos += (ring[index(pos + 1)] & 0xFF) + 2;
            n++;
        }
        return n;
    }

    /** 上限（"持续发送到上限"的那个上限） */
    public int capacity() {
        return capacity;
    }

    /** 因满被丢弃的消息条数（0 = 从未丢过；非 0 必须让调用方看到） */
    public synchronized long dropped() {
        return dropped;
    }

    /** 累计入队消息条数（= 发布进 guest 的 seq 字段） */
    public synchronized long appended() {
        return appended;
    }

    /** 累计已被消费的字节数（宿主侧 takeAll + guest 写回的 tail） */
    public synchronized long consumedBytes() {
        return consumed;
    }

    // ==================== guest 视角（guest 内存里那段窗口） ====================

    /** 单调写计数（环形下标 = 它 % {@link #capacity()}） */
    public synchronized int head() {
        return head;
    }

    /** 单调读计数（宿主镜像值；guest 写回后由 {@link #syncGuestTail} 更新） */
    public synchronized int tail() {
        return tail;
    }

    /** 待发字节数 = head - tail（无符号口径） */
    public synchronized int used() {
        return head - tail;
    }

    /** 状态位（READY / FULL / DROPPED） */
    public synchronized int state() {
        int s = STATE_READY;
        if (used() >= capacity) {
            s |= STATE_FULL;
        }
        if (dropped > 0) {
            s |= STATE_DROPPED;
        }
        return s;
    }

    /**
     * 环形数据区里"待发窗口"的起点下标（= {@code tail % capacity}）。
     *
     * <p>平台层按它写内存：窗口的连续拷贝从 {@code MSG_QUEUE_DATA_BASE + windowOffset()} 起写，
     * 写满环尾后剩下的部分回卷到 {@code MSG_QUEUE_DATA_BASE} 起（与 {@link #window()} 的字节顺序一致）。</p>
     */
    public synchronized int windowOffset() {
        return index(tail);
    }

    /** 待发窗口的连续拷贝（按环序，从 tail 到 head；跨环尾时已在内存里拼好） */
    public synchronized byte[] window() {
        final int used = used();
        if (used == 0) {
            return EMPTY;
        }
        final byte[] out = new byte[used];
        int at = index(tail);
        for (int i = 0; i < used; i++) {
            out[i] = ring[at];
            if (++at >= capacity) {
                at = 0;
            }
        }
        return out;
    }

    /** 头部的 A 段（magic + head），写成 guest 内存字节；平台层按 {@link #HOST_A_OFFSET} 写 */
    public synchronized byte[] guestHeaderA() {
        final byte[] out = new byte[HOST_A_BYTES];
        put32(out, OFF_MAGIC - HOST_A_OFFSET, MAGIC);
        put32(out, OFF_HEAD - HOST_A_OFFSET, head);
        return out;
    }

    /** 头部的 B 段（used / capacity / dropped / state / seq） */
    public synchronized byte[] guestHeaderB() {
        final byte[] out = new byte[HOST_B_BYTES];
        put32(out, OFF_USED - HOST_B_OFFSET, used());
        put32(out, OFF_CAPACITY - HOST_B_OFFSET, capacity);
        put32(out, OFF_DROPPED - HOST_B_OFFSET, (int) dropped);
        put32(out, OFF_STATE - HOST_B_OFFSET, state());
        put32(out, OFF_SEQ - HOST_B_OFFSET, (int) appended);
        return out;
    }

    /** 整个窗口（头 + 数据区）的初始镜像；平台层在开机时一次性写进 guest RAM */
    public synchronized byte[] initialImage() {
        final byte[] out = new byte[HEADER_BYTES + capacity];
        System.arraycopy(guestHeaderA(), 0, out, HOST_A_OFFSET, HOST_A_BYTES);
        System.arraycopy(guestHeaderB(), 0, out, HOST_B_OFFSET, HOST_B_BYTES);
        return out;
    }

    /**
     * guest 写回的 tail：把"已经消费掉的字节"从待发窗口里摘掉（空间回收）。
     *
     * <p>只有在 {@code (guestTail - tail) <= used} 时才接受 —— 否则说明 guest 读到了
     * 一个不自洽的值（外部改了内存 / 固件版本不匹配），此时<b>绝不动 tail</b>（动了就是把
     * 没发出去的数据判成已消费），只把 {@link #syncAnomalies()} 加一，让调用方打出来。</p>
     *
     * @return 是否真的回收了空间
     */
    public synchronized boolean syncGuestTail(int guestTail) {
        final long delta = (guestTail - tail) & 0xFFFF_FFFFL;
        if (delta == 0L) {
            return false;
        }
        if (delta > used()) {
            syncAnomalies++;
            return false;
        }
        tail = guestTail;
        consumed += delta;
        dirty = true;
        return true;
    }

    /** guest 写回的 tail 不自洽的次数（正常恒 0；非 0 必须让调用方看到） */
    public synchronized long syncAnomalies() {
        return syncAnomalies;
    }

    /** 宿主侧是否有变更还没发布进 guest RAM */
    public synchronized boolean isDirty() {
        return dirty;
    }

    /**
     * 一次"发布快照"：**数据窗口 + 写入偏移 + A/B 段头 + 入队计数**，在锁内一次算齐。
     *
     * <p>为什么必须原子：发布者（tick pump）要先把数据字节写进 guest RAM、再发布 head。
     * 若两件事之间插进来一次 {@link #append}，就会出现"head 已经指向新数据、而那段字节还没写下去"
     * 的撕裂视图 —— 固件会读到旧内容（偶发乱码/丢键，且极难复现）。锁内取齐 ⇒ 发布出去的
     * {@code head} 与 {@code window} 永远配套。</p>
     */
    public synchronized Snapshot snapshotForPublish() {
        return new Snapshot(window(), index(tail), guestHeaderA(), guestHeaderB(), appended);
    }

    /**
     * 发布完成（平台层写完头与数据后调）。
     *
     * <p>⚠ 必须带上**这次发布对应的 seq**：只有"自那一刻起没有新入队"才允许清脏。
     * 否则一次发布期间追加进来的消息会被静默吞掉（脏位被清、下一个 tick 不发布）——
     * 那正是"偶发丢一次按键"的典型来源。</p>
     *
     * @param publishedSeq {@link Snapshot#seq()}（本次发布覆盖到的入队计数）
     */
    public synchronized void markFlushed(long publishedSeq) {
        if (appended == publishedSeq) {
            dirty = false;
        }
    }

    /** 一次发布所需的一切（{@link #snapshotForPublish()} 在锁内算好；平台层拿它去写 guest RAM） */
    public record Snapshot(byte[] window, int offset, byte[] headerA, byte[] headerB, long seq) {
    }

    // ==================== 内部 ====================

    /** 单调计数 → 环形下标 */
    private int index(int counter) {
        final int m = counter % capacity;
        return m < 0 ? m + capacity : m;
    }

    private static void put32(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xFF);
        out[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        out[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        out[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static final byte[] EMPTY = new byte[0];
}
