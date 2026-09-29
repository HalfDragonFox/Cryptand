package com.hdf.cryptand.soc.link;

import java.util.List;

/**
 * ===== 设备链路（接口准入的拓扑层）离线闸门（2026-09-28，纯 Java 零 MC）=====
 *
 * <p>跑法：{@code ./gradlew :common:runLinkTest}</p>
 *
 * <p>钉住《接口准入》定案的规则：① 支持接口列表顺序即优先级、协商取交集里全局优先级最小者；
 * ② 源组件/中间组件角色；③ 级联上限 128；④ 链路速率木桶效应；⑤ 端点规范化（去空去重+按优先级排序）。</p>
 */
public final class SocLinkSelfTest {

    private static int passed;
    private static int failed;
    private static String section = "";

    public static void main(String[] args) {
        System.out.println("=== Device link self test (interface admission: protocol + roles + cascade + bucket rate) ===");

        protocolTable();
        negotiation();
        roles();
        normalization();
        connectBasics();
        cascade();
        bucketRate();
        teardown();
        twoSided();
        cardInCase();
        portKinds();
        portLimits();
        rowsRoundTrip();

        System.out.println("=== Link " + passed + "/" + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 端口连接上限 —— **由协议决定**（用户 2026-09-29 定案：「端点能连接设备是否具备多设备连接取决于协议，
     * 比如 UART 一般就是点对点」）。
     */
    private static void portLimits() {
        section = "端口连接上限（按协议）";
        check("多设备能力是协议属性：UART/DP/8080 = 1，I2C/SPI/USB/PCIE > 1",
                LinkProtocol.UART.maxDevices() == 1 && LinkProtocol.DP.maxDevices() == 1
                        && LinkProtocol.P8080.maxDevices() == 1 && LinkProtocol.I2C.maxDevices() > 1
                        && LinkProtocol.SPI.maxDevices() > 1 && LinkProtocol.USB.maxDevices() > 1
                        && LinkProtocol.PCIE.maxDevices() > 1);
        check("设备级端点（无 port，代表整台设备）不受约束",
                !screen().isFull(5, LinkProtocol.DP) && !gpuCard().isFull(3, LinkProtocol.DP));
        // UART：点对点 ⇒ 第二条被拒
        final SocLinkTable uartBus = SocLinkTable.of();
        final SocLinkTable.Endpoint uartPort = new SocLinkTable.Endpoint(CHIP_POS, "chip", "uart1",
                SocLinkRole.SOURCE, List.of(LinkProtocol.UART));
        final SocLinkTable.Endpoint dev1 = new SocLinkTable.Endpoint(new SocLinkTable.Pos(70, 64, 10),
                "terminal1", "uart1", SocLinkRole.SOURCE, List.of(LinkProtocol.UART));
        final SocLinkTable.Endpoint dev2 = new SocLinkTable.Endpoint(new SocLinkTable.Pos(71, 64, 10),
                "terminal2", "uart1", SocLinkRole.SOURCE, List.of(LinkProtocol.UART));
        check("UART（点对点）第一条成功", uartBus.connect(uartPort, dev1).ok());
        final SocLinkTable.Result second = uartBus.connect(uartPort, dev2);
        check("UART 第二条 ⇒ 拒绝（" + second.reason() + "）",
                !second.ok() && second.reason().contains("已经接满"));
        check("失败的连线没有副作用（size 仍 1）", uartBus.size() == 1);
        // I2C：多从总线 ⇒ 按协议上限挂满后才拒
        final SocLinkTable i2cBus = SocLinkTable.of();
        final SocLinkTable.Endpoint i2cPort = new SocLinkTable.Endpoint(CHIP_POS, "chip", "i2c1",
                SocLinkRole.SOURCE, List.of(LinkProtocol.I2C));
        int okCount = 0;
        for (int i = 0; i <= LinkProtocol.I2C.maxDevices(); i++) {
            if (i2cBus.connect(i2cPort, new SocLinkTable.Endpoint(new SocLinkTable.Pos(80 + i, 64, 10),
                    "sensor" + i, "i2c1", SocLinkRole.SOURCE, List.of(LinkProtocol.I2C))).ok()) {
                okCount++;
            }
        }
        check("I2C 按协议上限接满（" + LinkProtocol.I2C.maxDevices() + " 条）后拒绝下一条",
                okCount == LinkProtocol.I2C.maxDevices() && i2cBus.size() == LinkProtocol.I2C.maxDevices());
        check("显式上限可覆盖协议", new SocLinkTable.Endpoint(CHIP_POS, "chip", "usb1", SocLinkRole.SOURCE,
                List.of(LinkProtocol.USB), 2).effectiveMaxLinks(LinkProtocol.USB) == 2);
    }

    // ------------------------------------------------------------------ 协议表

    private static void protocolTable() {
        section = "协议表";
        check("优先级顺序 PCIE<DP<USB<SPI<UART<I2C<8080",
                LinkProtocol.PCIE.priority() < LinkProtocol.DP.priority()
                        && LinkProtocol.DP.priority() < LinkProtocol.USB.priority()
                        && LinkProtocol.USB.priority() < LinkProtocol.SPI.priority()
                        && LinkProtocol.SPI.priority() < LinkProtocol.UART.priority()
                        && LinkProtocol.UART.priority() < LinkProtocol.I2C.priority()
                        && LinkProtocol.I2C.priority() < LinkProtocol.P8080.priority());
        check("byId 大小写不敏感（PCIE/pcie）", LinkProtocol.byId("pcie") == LinkProtocol.PCIE
                && LinkProtocol.byId("Pcie") == LinkProtocol.PCIE);
        check("byId(8080) == P8080", LinkProtocol.byId("8080") == LinkProtocol.P8080);
        check("byId 未知/空返回 null", LinkProtocol.byId("hdmi") == null && LinkProtocol.byId(null) == null);
        boolean rates = true;
        for (final LinkProtocol p : LinkProtocol.values()) {
            rates &= p.bitsPerSecond() > 0 && p.id() != null && !p.id().isEmpty();
        }
        check("每个协议都有 id 与正速率", rates);
    }

    private static void negotiation() {
        section = "协商";
        final List<LinkProtocol> screen = List.of(LinkProtocol.DP, LinkProtocol.SPI, LinkProtocol.UART,
                LinkProtocol.I2C, LinkProtocol.P8080);
        final List<LinkProtocol> chip = List.of(LinkProtocol.PCIE, LinkProtocol.USB, LinkProtocol.SPI,
                LinkProtocol.UART, LinkProtocol.I2C);
        final List<LinkProtocol> gpuCard = List.of(LinkProtocol.PCIE, LinkProtocol.DP, LinkProtocol.SPI);
        check("屏 ↔ GPU 卡 = DP（屏支持五种、卡有 DP/SPI ⇒ DP 优先）",
                LinkProtocol.firstCommon(screen, gpuCard) == LinkProtocol.DP);
        check("GPU 卡 ↔ 芯片 = PCIE", LinkProtocol.firstCommon(gpuCard, chip) == LinkProtocol.PCIE);
        check("屏 ↔ 芯片 = SPI（没有公共的 DP/PCIE，退到 SPI）",
                LinkProtocol.firstCommon(screen, chip) == LinkProtocol.SPI);
        check("与参数顺序无关",
                LinkProtocol.firstCommon(gpuCard, chip) == LinkProtocol.firstCommon(chip, gpuCard));
        check("无交集 ⇒ null", LinkProtocol.firstCommon(List.of(LinkProtocol.P8080),
                List.of(LinkProtocol.PCIE)) == null);
        check("空/null 列表 ⇒ null", LinkProtocol.firstCommon(List.of(), chip) == null
                && LinkProtocol.firstCommon(null, chip) == null);
    }

    private static void roles() {
        section = "角色";
        check("SOURCE.isMiddle() = false / MIDDLE.isMiddle() = true",
                !SocLinkRole.SOURCE.isMiddle() && SocLinkRole.MIDDLE.isMiddle());
        check("byId 还原角色", SocLinkRole.byId("middle") == SocLinkRole.MIDDLE
                && SocLinkRole.byId("SOURCE") == SocLinkRole.SOURCE);
        check("byId 未知返回 null", SocLinkRole.byId("card") == null && SocLinkRole.byId(null) == null);
    }

    private static void normalization() {
        section = "端点规范化";
        final SocLinkTable.Endpoint e = ep(0, 0, 0, "screen", SocLinkRole.SOURCE,
                java.util.Arrays.asList(LinkProtocol.I2C, LinkProtocol.DP, null, LinkProtocol.DP, LinkProtocol.P8080));
        check("去 null、去重", e.interfaces().size() == 3);
        check("按优先级升序（DP<I2C<8080）",
                e.interfaces().get(0) == LinkProtocol.DP
                        && e.interfaces().get(1) == LinkProtocol.I2C
                        && e.interfaces().get(2) == LinkProtocol.P8080);
        check("supports 命中/不命中", e.supports(LinkProtocol.DP) && !e.supports(LinkProtocol.PCIE));
        check("null 接口列表也安全", ep(1, 0, 0, "x", SocLinkRole.SOURCE, null).interfaces().isEmpty());
    }

    // ------------------------------------------------------------------ 连线

    private static SocLinkTable.Endpoint ep(int x, int y, int z, String id, SocLinkRole role, List<LinkProtocol> p) {
        return new SocLinkTable.Endpoint(new SocLinkTable.Pos(x, y, z), id, role, p);
    }

    private static final List<LinkProtocol> SCREEN = List.of(LinkProtocol.DP, LinkProtocol.SPI,
            LinkProtocol.UART, LinkProtocol.I2C, LinkProtocol.P8080);
    private static final List<LinkProtocol> CHIP = List.of(LinkProtocol.PCIE, LinkProtocol.USB,
            LinkProtocol.SPI, LinkProtocol.UART, LinkProtocol.I2C);
    private static final List<LinkProtocol> GPU_CARD = List.of(LinkProtocol.PCIE, LinkProtocol.DP,
            LinkProtocol.SPI);

    private static final SocLinkTable.Pos SCREEN_POS = new SocLinkTable.Pos(10, 64, 10);
    private static final SocLinkTable.Pos CARD_POS = new SocLinkTable.Pos(11, 64, 10);
    private static final SocLinkTable.Pos CHIP_POS = new SocLinkTable.Pos(12, 64, 10);
    private static final SocLinkTable.Pos CHIP2_POS = new SocLinkTable.Pos(20, 64, 10);

    private static SocLinkTable.Endpoint screen() {
        return ep(10, 64, 10, "screen", SocLinkRole.SOURCE, SCREEN);
    }

    private static SocLinkTable.Endpoint gpuCard() {
        return ep(11, 64, 10, "card_gpu", SocLinkRole.MIDDLE, GPU_CARD);
    }

    private static SocLinkTable.Endpoint chip() {
        return ep(12, 64, 10, "chip", SocLinkRole.SOURCE, CHIP);
    }

    private static void connectBasics() {
        section = "连线基础";
        final SocLinkTable t = SocLinkTable.of();
        final SocLinkTable.Result screenToCard = t.connect(screen(), gpuCard());
        check("屏 ↔ GPU 卡 成功（" + screenToCard.reason() + "）", screenToCard.ok());
        check("协议是 DP", screenToCard.protocol() == LinkProtocol.DP);
        check("提示里写明含中间组件", screenToCard.reason().contains("中间组件"));
        final SocLinkTable.Result cardToChip = t.connect(gpuCard(), chip());
        check("GPU 卡 ↔ 芯片 成功、协议 PCIE",
                cardToChip.ok() && cardToChip.protocol() == LinkProtocol.PCIE);
        check("size=2", t.size() == 2);
        check("isLinked(屏/卡/芯片) 全真",
                t.isLinked(SCREEN_POS) && t.isLinked(CARD_POS) && t.isLinked(CHIP_POS));
        check("isLinked(未连的点) 假", !t.isLinked(new SocLinkTable.Pos(99, 64, 10)));
        check("linksAt(卡) = 2 条（双向可连）", t.linksAt(CARD_POS).size() == 2);
        check("重复连同一对 ⇒ 拒绝（" + t.connect(screen(), gpuCard()).reason() + "）",
                !t.connect(screen(), gpuCard()).ok());
        check("自连 ⇒ 拒绝", !t.connect(chip(), chip()).ok());
        check("无公共协议 ⇒ 拒绝（屏[DP..] vs 只认 PCIE 的卡）",
                !t.connect(screen(), ep(30, 64, 10, "card_x", SocLinkRole.MIDDLE,
                        List.of(LinkProtocol.PCIE))).ok());
        check("失败调用没有副作用（size 仍 2）", t.size() == 2);
        check("hops(屏→芯片) = 2（中间一张卡）", t.hops(SCREEN_POS, CHIP_POS) == 2);
        check("middleHops(屏→芯片) = 1（只有卡算中间段）", t.middleHops(SCREEN_POS, CHIP_POS) == 1);
        check("hops 同点 = 0，不连通 = -1",
                t.hops(SCREEN_POS, SCREEN_POS) == 0
                        && t.hops(SCREEN_POS, new SocLinkTable.Pos(50, 64, 50)) == -1);
    }

    private static void cascade() {
        section = "级联";
        // 127 张卡串成一条链：芯片 → 卡1 → … → 卡127
        final SocLinkTable t = SocLinkTable.of();
        SocLinkTable.Endpoint prev = chip();
        for (int i = 1; i <= 127; i++) {
            final SocLinkTable.Endpoint card = ep(1000 + i, 64, 0, "card" + i, SocLinkRole.MIDDLE, GPU_CARD);
            check_quiet(t.connect(prev, card).ok());
            prev = card;
        }
        check("127 段全部连上（size=" + t.size() + "）", t.size() == 127);
        check("链尾到芯片的跳数 = 127", t.hops(CHIP_POS, prev.pos()) == 127);
        check("中间段计数 = 127", t.middleHops(CHIP_POS, prev.pos()) == 127);
        // 再连一条回到芯片：形成 128 段的环 ⇒ 允许（上限 128）
        final SocLinkTable.Result loopOk = t.connect(prev, chip());
        check("成环 128 段 ⇒ 允许（" + loopOk.reason() + "）", loopOk.ok());
        // 129 段串成一条链后想闭合 ⇒ 环长 130 > 128 ⇒ 拒绝（单开一张表，避免上面那条捷径环影响最短路径）
        final SocLinkTable deep = SocLinkTable.of();
        SocLinkTable.Endpoint tail = chip();
        for (int i = 1; i <= 129; i++) {
            final SocLinkTable.Endpoint card = ep(3000 + i, 64, 0, "deep" + i, SocLinkRole.MIDDLE, GPU_CARD);
            deep.connect(tail, card);
            tail = card;
        }
        check("129 段链的跳数 = 129", deep.hops(CHIP_POS, tail.pos()) == 129);
        final SocLinkTable.Result tooDeep = deep.connect(tail, chip());
        check("想闭合 130 段的环 ⇒ 拒绝（" + tooDeep.reason() + "）",
                !tooDeep.ok() && tooDeep.reason().contains("级联超过上限"));
    }

    // ------------------------------------------------------------ 两端都连

    /**
     * 用户 2026-09-28 定案：「扩展卡默认插上不连接外设和 RV，空连接，需要两边都连上」。
     *
     * <p>插上 ≠ 连上；一张卡要接入虚拟机，必须一边连芯片、一边连外设。</p>
     */
    private static void twoSided() {
        section = "两端都连";
        final SocLinkTable t = SocLinkTable.of();
        final SocLinkTable.Pos chip = new SocLinkTable.Pos(0, 64, 0);
        final SocLinkTable.Pos card = new SocLinkTable.Pos(1, 64, 0);
        final SocLinkTable.Pos screen = new SocLinkTable.Pos(2, 64, 0);
        final SocLinkTable.Endpoint chipEp = new SocLinkTable.Endpoint(chip, SocLinkTable.CHIP_DEVICE,
                SocLinkRole.SOURCE, List.of(LinkProtocol.PCIE, LinkProtocol.USB, LinkProtocol.SPI));
        final SocLinkTable.Endpoint cardEp = new SocLinkTable.Endpoint(card, "card_gpu",
                SocLinkRole.SOURCE, List.of(LinkProtocol.PCIE, LinkProtocol.DP, LinkProtocol.SPI));
        final SocLinkTable.Endpoint screenEp = new SocLinkTable.Endpoint(screen, "screen",
                SocLinkRole.SOURCE, List.of(LinkProtocol.DP, LinkProtocol.SPI));

        check("空连接：插上未连线 ⇒ 两侧都没连上", !t.linkedToChip(card) && !t.linkedToPeripheral(card)
                && !t.fullyLinked(card));
        check("只连芯片一侧 ⇒ 仍不算接入", t.connect(cardEp, chipEp).ok()
                && t.linkedToChip(card) && !t.linkedToPeripheral(card) && !t.fullyLinked(card));
        // ⚠ 屏是**外设端**，它只需要接上（isLinked）—— "两端都连"是**卡**（中间器件）的准入判据。
        check("补上外设一侧 ⇒ 卡两端都连成立、屏作为外设端已接上", t.connect(cardEp, screenEp).ok()
                && t.fullyLinked(card) && t.isLinked(screen) && !t.fullyLinked(screen));

        final SocLinkTable.Link lc = t.findEdge(card, chip);
        final SocLinkTable.Link ls = t.findEdge(card, screen);
        check("协议按优先级自动选：卡↔芯片 = PCIE、卡↔屏 = DP", lc != null && ls != null
                && lc.protocol() == LinkProtocol.PCIE && ls.protocol() == LinkProtocol.DP);
        check("芯片侧不算外设侧（芯片自己不是 fullyLinked）", !t.fullyLinked(chip) && t.linkedToPeripheral(chip));
        check("端点快照可按坐标取回（UI 显示用）", t.endpointAt(card) != null
                && SocLinkTable.CHIP_DEVICE.equals(t.endpointAt(chip).deviceId()));
        check("断开外设一侧 ⇒ 退回未接入", t.disconnect(card, screen) && !t.fullyLinked(card));
        check("全断后端点快照被清掉", t.disconnectAll(card) == 1 && t.endpointAt(card) == null);
    }

    // ---------------------------------------------------- 卡在机箱（端点身份）

    /**
     * 用户 2026-09-28：「扩展卡默认插上不连接外设和 RV，空连接，需要两边都连上」。
     *
     * <p>扩展卡在机箱**槽位**里，和机箱的芯片端点**同坐标** —— 这条闸门钉住"端点身份 = 坐标 + 设备类型"：
     * 同坐标的两个端点可以连线，且只有两条线都在（芯片侧 + 外设侧）才算接入。</p>
     */
    private static void cardInCase() {
        section = "卡在机箱（端点身份）";
        final SocLinkTable t = SocLinkTable.of();
        final SocLinkTable.Pos casePos = new SocLinkTable.Pos(10, 64, 10);
        final SocLinkTable.Pos screenPos = new SocLinkTable.Pos(12, 64, 10);
        final SocLinkTable.Endpoint chip = new SocLinkTable.Endpoint(casePos, SocLinkTable.CHIP_DEVICE,
                SocLinkRole.SOURCE, List.of(LinkProtocol.PCIE, LinkProtocol.USB, LinkProtocol.SPI));
        final SocLinkTable.Endpoint card = new SocLinkTable.Endpoint(casePos, "card_gpu",
                SocLinkRole.MIDDLE, List.of(LinkProtocol.PCIE, LinkProtocol.DP, LinkProtocol.SPI));
        final SocLinkTable.Endpoint screen = new SocLinkTable.Endpoint(screenPos, "screen",
                SocLinkRole.SOURCE, List.of(LinkProtocol.DP, LinkProtocol.SPI));

        check("同坐标两个端点：坐标相同但身份不同 ⇒ 不是同一个端点",
                !chip.key().equals(card.key()) && chip.key().pos().equals(card.key().pos()));
        check("卡 ↔ 芯片（同坐标）可以连，协议按优先级 = PCIE", t.connect(card, chip).ok()
                && t.findEdge(card.key(), chip.key()).protocol() == LinkProtocol.PCIE);
        check("只连芯片一侧 ⇒ 卡仍未接入", !t.fullyLinked(card.key()) && t.linkedToChip(card.key()));
        check("卡 ↔ 屏 再连 ⇒ 卡两端都连、屏作为外设端已接上", t.connect(card, screen).ok()
                && t.fullyLinked(card.key()) && t.isLinked(screen.key()) && !t.fullyLinked(screen.key()));
        check("坐标上有两个端点键（机箱 + 卡），坐标级取不回单端点", t.keysAt(casePos).size() == 2
                && t.endpointAt(casePos) == null);
        check("坐标级聚合：该机箱坐标「有端点两端都连」为真", t.fullyLinked(casePos));
        check("键级跳数：卡→芯片 1、卡→屏 1、芯片→屏 2",
                t.hops(card.key(), chip.key()) == 1 && t.hops(card.key(), screen.key()) == 1
                        && t.hops(chip.key(), screen.key()) == 2);
        check("路径速率木桶：芯片→屏 = min(PCIE, DP) = DP（PCIe 更快，瓶颈在 DP）",
                t.pathBitsPerSecond(chip.key(), screen.key()) == LinkProtocol.DP.bitsPerSecond()
                        && LinkProtocol.PCIE.bitsPerSecond() > LinkProtocol.DP.bitsPerSecond());
        check("断开 卡↔芯片 ⇒ 卡退回未接入，屏那条线还在", t.disconnect(card.key(), chip.key())
                && !t.fullyLinked(card.key()) && t.isLinked(screen.key()));
        check("按坐标断全部 ⇒ 卡↔屏 也断掉、坐标上不再有端点",
                t.disconnectAll(casePos) == 1 && t.keysAt(casePos).isEmpty());
    }

    private static void check_quiet(boolean ok) {
        if (!ok) {
            failed++;
            System.out.println("  [FAIL] " + section + " · 级联链中某段连接失败");
        } else {
            passed++;
        }
    }

    private static void bucketRate() {
        section = "木桶速率";
        final SocLinkTable t = SocLinkTable.of();
        final SocLinkTable.Endpoint slow = ep(40, 64, 0, "card_uart", SocLinkRole.MIDDLE,
                List.of(LinkProtocol.PCIE, LinkProtocol.UART));
        final SocLinkTable.Endpoint chipB = ep(41, 64, 0, "chip", SocLinkRole.SOURCE, CHIP);
        check("芯片 ↔ 慢卡 = PCIE", t.connect(chip(), slow).protocol() == LinkProtocol.PCIE);
        // 慢卡 ↔ 第二块芯片：公共协议只有 UART（第二块芯片没有 DP）
        final SocLinkTable.Endpoint chipSlow = ep(42, 64, 0, "chip_slow", SocLinkRole.SOURCE,
                List.of(LinkProtocol.UART, LinkProtocol.I2C));
        final SocLinkTable.Result r = t.connect(slow, chipSlow);
        check("慢卡 ↔ 只认 UART 的芯片 = UART", r.ok() && r.protocol() == LinkProtocol.UART);
        check("整条路径速率 = UART（木桶最低）",
                t.pathBitsPerSecond(CHIP_POS, chipSlow.pos()) == LinkProtocol.UART.bitsPerSecond());
        check("直接一段是 PCIE 速率",
                t.pathBitsPerSecond(CHIP_POS, slow.pos()) == LinkProtocol.PCIE.bitsPerSecond());
        check("不连通 ⇒ -1", t.pathBitsPerSecond(CHIP_POS, new SocLinkTable.Pos(77, 0, 0)) == -1);
    }

    private static void teardown() {
        section = "断开";
        final SocLinkTable t = SocLinkTable.of();
        t.connect(screen(), gpuCard());
        t.connect(gpuCard(), chip());
        check("精确断开(屏,卡) 成功", t.disconnect(SCREEN_POS, CARD_POS));
        check("反过来写也认", !t.disconnect(CARD_POS, SCREEN_POS) && t.disconnect(CARD_POS, CHIP_POS));
        check("全断后 size=0", t.size() == 0);
        t.connect(screen(), gpuCard());
        t.connect(gpuCard(), chip());
        check("disconnectAll(卡) 断 2 条", t.disconnectAll(CARD_POS) == 2 && t.size() == 0);
        check("再 disconnectAll 返回 0", t.disconnectAll(CARD_POS) == 0);
        t.connect(screen(), gpuCard());
        check("endpoints() 含两端", t.endpoints().contains(SCREEN_POS) && t.endpoints().contains(CARD_POS));
        check("links() 是副本", mutateCopy(t));
        t.clear();
        check("clear() 清空", t.size() == 0 && t.endpoints().isEmpty());
        check("同一端点未连时 linksAt 为空", t.linksAt(SCREEN_POS).isEmpty());
    }

    /** links() 返回的是不可变副本：外部误改必须炸，而不是悄悄改坏链路表。 */
    private static boolean mutateCopy(SocLinkTable t) {
        try {
            t.links().clear();
            return false;
        } catch (UnsupportedOperationException expected) {
            return t.size() == 1;
        }
    }

    // ------------------------------------------------------------ 接口点与持久化行

    /**
     * 接口点种类的唯一判据（2026-09-29 补齐）：<b>颜色 = 协议，同色才能连</b>。
     *
     * <p>判据在 common（{@link PortKind}），面板/画布/链路表都读它，避免两边各调一套色。</p>
     */
    private static void portKinds() {
        section = "接口点种类";
        boolean unique = true;
        final java.util.Set<Integer> colors = new java.util.HashSet<>();
        final java.util.Set<LinkProtocol> protocols = new java.util.HashSet<>();
        for (final PortKind k : PortKind.values()) {
            if (k == PortKind.GENERIC) {
                continue;   // 设计期通用点（protocol() 为 null）：不属宿主链路协议表，单独在 HwCanvas 闸门里钉
            }
            unique &= colors.add(k.argb()) && protocols.add(k.protocol());
            unique &= PortKind.of(k.protocol()) == k;
        }
        check("颜色与协议一一对应（8 种宿主接口；通用点是设计期专用）",
                unique && PortKind.GENERIC.protocol() == null);
        check("端口 id：RV 固定 rv，其余带序号",
                PortKind.RV.portId(1).equals("rv") && PortKind.RV.portId(7).equals("rv")
                        && PortKind.DP.portId(3).equals("dp3"));
        check("byId 按前缀认且忽略大小写（dp1/DP12 ⇒ DP，usb2 ⇒ USB）",
                PortKind.byId("dp1") == PortKind.DP && PortKind.byId("DP12") == PortKind.DP
                        && PortKind.byId("usb2") == PortKind.USB);
        check("认不出的端口 ⇒ null（不猜颜色，宁可拒绝连）",
                PortKind.byId("ghost") == null && PortKind.byId("") == null && PortKind.byId(null) == null);
        check("sameKind 只在同一种类时为真",
                PortKind.sameKind("rv", "rv") && PortKind.sameKind("dp1", "dp4")
                        && !PortKind.sameKind("rv", "dp1") && !PortKind.sameKind("ghost", "rv"));
        check("colorOf 认不出给灰色而不是抛",
                PortKind.colorOf(null) == 0xFF808080
                        && PortKind.colorOf(LinkProtocol.PCIE) == PortKind.RV.argb());
        check("画布判据 canConnect 只在同色时为真",
                HwCanvasLayout.canConnect(PortKind.DP, PortKind.DP)
                        && !HwCanvasLayout.canConnect(PortKind.DP, PortKind.RV)
                        && !HwCanvasLayout.canConnect(null, PortKind.DP));
    }

    /**
     * 链路行往返（2026-09-29 修「端口身份丢了」的回归网）：
     * 导出 → 重放后<b>端口身份与协议必须原样回来</b>，坏行丢弃要回报。
     */
    private static void rowsRoundTrip() {
        section = "链路行往返";
        final SocLinkTable t = SocLinkTable.of();
        t.connect(portEp(0, 64, 0, "chip", "rv"), portEp(1, 64, 0, "card_gpu", "rv"));
        t.connect(portEp(1, 64, 0, "card_gpu", "dp1"), portEp(2, 64, 0, "screen", "dp1"));
        final List<SocLinkTable.Row> rows = t.rows();
        check("导出的行带端口（" + describeRows(rows) + "）",
                rows.size() == 2 && rows.get(0).aPort().equals("rv")
                        && rows.get(1).aPort().equals("dp1") && rows.get(1).bPort().equals("dp1"));
        check("行里带协商出的协议 id",
                rows.get(0).protocolId().equals(LinkProtocol.PCIE.id())
                        && rows.get(1).protocolId().equals(LinkProtocol.DP.id()));

        final SocLinkTable t2 = SocLinkTable.of();
        final List<String> warns = new java.util.ArrayList<>();
        final int n = t2.importRows(rows, SocLinkSelfTest::fakeResolve, warns::add);
        check("全部重放成功且无告警", n == 2 && warns.isEmpty() && t2.size() == 2);
        check("重放后导出与原始行逐字段相等", t2.rows().equals(rows));
        check("端口级查询命中（端口身份真的保持了）",
                t2.findEdge(key(0, 64, 0, "chip", "rv"), key(1, 64, 0, "card_gpu", "rv")) != null);
        check("不满端口的键查不到带端口的那条（端口参与身份匹配）",
                t2.findEdge(key(0, 64, 0, "chip", ""), key(1, 64, 0, "card_gpu", "rv")) == null);

        final List<String> warns2 = new java.util.ArrayList<>();
        final SocLinkTable t3 = SocLinkTable.of();
        t3.importRows(List.of(rows.get(0),
                new SocLinkTable.Row(new SocLinkTable.Pos(5, 64, 0), "ghost", "rv",
                        new SocLinkTable.Pos(0, 64, 0), "chip", "rv", "pcie")),
                SocLinkSelfTest::fakeResolve, warns2::add);
        check("认不出的端点被丢弃并回报（" + warns2.size() + " 条告警）",
                t3.size() == 1 && warns2.size() == 1 && warns2.get(0).contains("已不存在"));

        final List<String> warns3 = new java.util.ArrayList<>();
        final SocLinkTable t4 = SocLinkTable.of();
        t4.importRows(List.of(new SocLinkTable.Row(new SocLinkTable.Pos(0, 64, 0), "chip", "rv",
                new SocLinkTable.Pos(1, 64, 0), "card_gpu", "rv", "usb")),
                SocLinkSelfTest::fakeResolve, warns3::add);
        check("协议与存档不一致 ⇒ 仍然导入但告警",
                t4.size() == 1 && warns3.size() == 1 && warns3.get(0).contains("不一致"));
        check("空行表 ⇒ 不导入、不抛", t4.importRows(null, SocLinkSelfTest::fakeResolve, null) == 0
                && SocLinkTable.of().importRows(List.of(), SocLinkSelfTest::fakeResolve, null) == 0);
    }

    /** 假设备表（纯 Java）：只有这三台设备在表里，端口 id 前缀决定协议 —— 与真实表口径一致。 */
    private static final java.util.Set<String> FAKE_DEVICES =
            java.util.Set.of("chip", "card_gpu", "screen");

    private static SocLinkTable.Endpoint fakeResolve(SocLinkTable.Pos pos, String deviceId, String port) {
        if (!FAKE_DEVICES.contains(deviceId)) {
            return null;      // 设备表里没有它 ⇒ 认不出（世界读盘时这条线丢弃）
        }
        final PortKind k = PortKind.byId(port);
        if (k == null) {
            return null;
        }
        return new SocLinkTable.Endpoint(pos, deviceId, port, SocLinkRole.SOURCE, List.of(k.protocol()));
    }

    private static SocLinkTable.Endpoint portEp(int x, int y, int z, String dev, String port) {
        return fakeResolve(new SocLinkTable.Pos(x, y, z), dev, port);
    }

    private static SocLinkTable.EndpointKey key(int x, int y, int z, String dev, String port) {
        return new SocLinkTable.EndpointKey(new SocLinkTable.Pos(x, y, z), dev, port);
    }

    private static String describeRows(List<SocLinkTable.Row> rows) {
        final StringBuilder sb = new StringBuilder();
        for (final SocLinkTable.Row r : rows) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(r.aDevice()).append(':').append(r.aPort())
                    .append(" -> ").append(r.bDevice()).append(':').append(r.bPort());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 工具

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok] " + section + " · " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + section + " · " + name);
        }
    }
}
