package com.hdf.cryptand.soc.bios;

import com.hdf.cryptand.soc.os.Programs;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.nio.charset.StandardCharsets;

/**
 * ===== Lua BIOS 的离线闸门（2026-09-26，纯 Java 零 MC）=====
 *
 * <p>为什么必须补这道闸门：{@code bios.lua} 是 <b>Lua 架构下机器的第一段代码</b>，而它此前只能靠
 * "起客户端 → 插盘 → 开机 → 看屏幕"来验证。于是同一类 bug 反复出现，且每次都伪装成别的问题：</p>
 * <ul>
 *   <li>句柄判据写成 {@code type(handle) ~= "number"} ⇒ 真盘永远"没有 /init.lua"
 *       （OC 的 filesystem.open 返回的是 <b>userdata</b>）；</li>
 *   <li>只列"邻居"文件系统 ⇒ 机箱内部的 OpenOS 盘看不见；</li>
 *   <li>不把引导盘地址交给系统 ⇒ 真 OpenOS 的 {@code /init.lua:2} 直接
 *       {@code attempt to call a nil value (field 'getBootAddress')}。</li>
 * </ul>
 *
 * <p>本闸门用 <b>luaj</b> 跑<b>游戏里同一份字节</b>（{@link Programs#read(String)}，与
 * {@code CryptandOcDrivers.luaBios()} 同源），周围配一个假的 OC 组件世界：一颗 EEPROM、
 * 一个假 GPU、若干块假盘。关键的真实性在于：</p>
 * <ul>
 *   <li>盘的句柄是**真 userdata**（由 Java 侧 {@link LuaValue#userdataOf} 造），
 *       所以"老判据 {@code type(handle) == "number"} 会误杀"这件事能被复现；</li>
 *   <li>盘上的 {@code /init.lua} 与 OC 仓库的 {@code openos/init.lua} <b>同形</b>：
 *       第 2 行就是 {@code computer.getBootAddress()} —— 真机报错的那一行。</li>
 * </ul>
 *
 * <p>跑法：{@code ./gradlew :common:runBiosLuaTest}（luaj 只进本闸门的 classpath，
 * common 主代码仍然零第三方依赖）。</p>
 */
public final class BiosLuaSelfTest {

    private BiosLuaSelfTest() {
    }

    /**
     * 闸门脚本：全部"世界"都在 Lua 里搭（假组件、假盘、假屏幕），断言也写在里面 ——
     * Java 侧只提供两样 Lua 造不出来的东西：bios.lua 的字节、以及**真 userdata**。
     */
    private static final String SCRIPT = """
            local BIOS = __bios_source()
            local INIT = __init_source()

            local passed, failed = 0, 0
            local failures = {}

            local function expect(cond, msg)
              if cond then
                passed = passed + 1
              else
                failed = failed + 1
                failures[#failures + 1] = msg
              end
            end

            -- 真 OpenOS 盘的 /init.lua：与 OC 仓库 openos/init.lua 同形（第 2 行 = getBootAddress）
            local OPENOS_INIT = [[
            do
              local addr, invoke = computer.getBootAddress(), component.invoke
              if type(addr) ~= "string" or #addr == 0 then
                error("no boot address", 0)
              end
              local handle = invoke(addr, "open", "/lib/core/boot.lua")
              if handle == nil then
                error("cannot open /lib/core/boot.lua", 0)
              end
              local first = invoke(addr, "read", handle, 1024)
              invoke(addr, "close", handle)
              return "booted:" .. addr .. ":" .. tostring(first)
            end
            ]]

            -- ===== 假世界 =====
            local function makeWorld(opts)
              local world = {}
              world.eepromData = opts.remembered or ""
              world.eepromWrites = {}
              world.screen = {}
              world.disks = opts.disks or {}
              world.invokes = {}
              world.handleTypes = {}

              local function listOf(name, exact)
                local addresses = {}
                if name == "filesystem" then
                  for _, d in ipairs(world.disks) do
                    addresses[#addresses + 1] = d.address
                  end
                elseif name == "eeprom" then
                  addresses[#addresses + 1] = "eeprom-0"
                elseif name == "gpu" then
                  addresses[#addresses + 1] = "gpu-0"
                elseif name == "screen" then
                  addresses[#addresses + 1] = "screen-0"
                end
                local i = 0
                return function()
                  i = i + 1
                  return addresses[i]
                end
              end

              local handles = {}
              local nextHandle = 0

              local function diskByAddress(address)
                for _, d in ipairs(world.disks) do
                  if d.address == address then
                    return d
                  end
                end
                return nil
              end

              local component = {}
              component.list = listOf
              component.invoke = function(address, method, ...)
                world.invokes[#world.invokes + 1] = address .. ":" .. tostring(method)
                if address == "eeprom-0" then
                  if method == "get" then
                    return BIOS
                  elseif method == "getData" then
                    return world.eepromData
                  elseif method == "setData" then
                    local value = ...
                    world.eepromData = tostring(value or "")
                    world.eepromWrites[#world.eepromWrites + 1] = world.eepromData
                    return
                  end
                  error("eeprom: unexpected method " .. tostring(method))
                elseif address == "gpu-0" then
                  if method == "bind" then
                    return true
                  elseif method == "set" then
                    local _, y, text = ...
                    world.screen[y] = text
                    return true
                  end
                  error("gpu: unexpected method " .. tostring(method))
                end
                local d = diskByAddress(address)
                if not d then
                  error("no such component: " .. tostring(address))
                end
                if method == "getLabel" then
                  return d.label
                elseif method == "exists" then
                  local path = ...
                  return d.files[path] ~= nil
                elseif method == "open" then
                  local path = ...
                  local content = d.files[path]
                  if content == nil then
                    return nil, "file not found"
                  end
                  -- ★ OC 的 filesystem.open 返回 userdata（不是 number）——
                  --   老判据 type(handle) ~= "number" 就是在这里把真盘判死的。
                  nextHandle = nextHandle + 1
                  local h = __make_ud()
                  world.handleTypes[#world.handleTypes + 1] = type(h)
                  handles[h] = { content = content, pos = 1 }
                  return h
                elseif method == "read" then
                  local h, count = ...
                  local st = handles[h]
                  if not st then
                    error("bad handle")
                  end
                  if st.pos > #st.content then
                    return nil
                  end
                  local chunk = st.content:sub(st.pos, st.pos + (count or 1024) - 1)
                  st.pos = st.pos + #chunk
                  return chunk
                elseif method == "size" then
                  -- 真 OC 的 filesystem 组件有 size(path)：bios.lua 用它报出引导记录的字节数
                  local path = ...
                  local content = d.files[path]
                  if content == nil then
                    error("file not found")
                  end
                  return #content
                elseif method == "close" then
                  local h = ...
                  handles[h] = nil
                  return true
                end
                error("filesystem: unexpected method " .. tostring(method))
              end

              local computer = {}
              return world, component, computer
            end

            -- useGlobals = true ⇒ 模拟 **OC 的 LuaJ 架构**（BIOS 的全局环境就是真 Globals，
            --   load 不传环境）；false ⇒ 模拟**原生 LuaC 架构**（BIOS 的 _ENV 是一张沙箱表）。
            -- 两种都必须过：我们的 BIOS 用 _ENV or _G 把"自己看到的环境"显式传给盘上的程序。
            local function runBios(world, component, computer, useGlobals)
              local env
              if useGlobals then
                _G.component = component
                _G.computer = computer
              else
                env = setmetatable({ component = component, computer = computer }, { __index = _G })
              end
              local chunk, err
              if env then
                chunk, err = load(BIOS, "=bios", "t", env)
              else
                chunk, err = load(BIOS, "=bios", "t")
              end
              if not chunk then
                _G.component = nil
                _G.computer = nil
                return nil, "load failed: " .. tostring(err)
              end
              local ok, result = pcall(chunk)
              _G.component = nil
              _G.computer = nil
              if not ok then
                return nil, tostring(result)
              end
              return result, nil
            end

            local function openosDisk(address, label)
              return {
                address = address,
                label = label or "OpenOS",
                files = { ["/init.lua"] = OPENOS_INIT, ["/lib/core/boot.lua"] = "-- boot script" },
              }
            end

            -- ===== 用例 1：空 EEPROM + 一块真 OpenOS 盘 ⇒ 找到盘、地址进 EEPROM、盘上 init.lua 真跑起来 =====
            do
              local world, component, computer = makeWorld({ disks = { openosDisk("disk-openos") } })
              local result, err = runBios(world, component, computer)
              expect(err == nil, "用例1: BIOS 不应报错，实际: " .. tostring(err))
              expect(tostring(result):sub(1, 7) == "booted:", "用例1: 盘上 /init.lua 应被真执行，实际: " .. tostring(result))
              expect(tostring(result):find("disk-openos", 1, true) ~= nil,
                     "用例1: init.lua 读到的引导盘地址应是 disk-openos，实际: " .. tostring(result))
              expect(world.eepromWrites[#world.eepromWrites] == "disk-openos",
                     "用例1: 引导盘地址应写回 EEPROM 的 data 段")
              expect(world.screen[1] == "booting /init.lua", "用例1: 屏幕应报告引导路径，实际: " .. tostring(world.screen[1]))
              local sawRead = false
              for _, v in ipairs(world.invokes) do
                if v == "disk-openos:read" then sawRead = true end
              end
              expect(sawRead, "用例1: 应以 userdata 句柄成功读过盘")
              expect(world.handleTypes[1] == "userdata", "用例1: 盘句柄必须是 userdata（真 OC 就是这样）")
            end

            -- ===== 用例 2：EEPROM 记住了盘 ⇒ 先试它，且地址没变就不重复写 =====
            do
              local world, component, computer = makeWorld({
                remembered = "disk-openos",
                disks = { openosDisk("disk-other"), openosDisk("disk-openos") },
              })
              local result, err = runBios(world, component, computer)
              expect(err == nil, "用例2: 不应报错: " .. tostring(err))
              local firstDiskCall
              for _, v in ipairs(world.invokes) do
                if v:find("disk-openos:", 1, true) == 1 then
                  firstDiskCall = v
                  break
                end
              end
              expect(firstDiskCall == "disk-openos:exists",
                     "用例2: 第一个盘操作应是试 EEPROM 记住的那块，实际: " .. tostring(firstDiskCall))
              expect(#world.eepromWrites == 0, "用例2: 地址没变时不应重复写 EEPROM")
              expect(tostring(result):find("disk-openos", 1, true) ~= nil, "用例2: 引导的应是记住的那块盘")
            end

            -- ===== 用例 3：记住的盘被拔走 ⇒ 不卡住，扫到别的盘并改写记忆 =====
            do
              local world, component, computer = makeWorld({
                remembered = "disk-gone",
                disks = { openosDisk("disk-b") },
              })
              local result, err = runBios(world, component, computer)
              expect(err == nil, "用例3: 不应报错: " .. tostring(err))
              expect(world.eepromWrites[#world.eepromWrites] == "disk-b",
                     "用例3: 应改写为真正引导的盘，实际: " .. tostring(world.eepromWrites[#world.eepromWrites]))
              expect(tostring(result):find("disk-b", 1, true) ~= nil, "用例3: 应引导到 disk-b")
            end

            -- ===== 用例 4：没有可引导盘 ⇒ 明确报错，并在屏幕上列出看到的盘（不静默） =====
            do
              local world, component, computer = makeWorld({
                disks = { { address = "disk-blank", label = "BLANK", files = {} } },
              })
              local result, err = runBios(world, component, computer)
              expect(result == nil and err ~= nil, "用例4: 没有 init.lua 时必须报错")
              expect(tostring(err):find("no /init.lua", 1, true) ~= nil, "用例4: 错误信息应说明找不到 init.lua，实际: " .. tostring(err))
              expect(tostring(world.screen[1]):find("no bootable", 1, true) ~= nil,
                     "用例4: 屏幕应写明没有可引导盘，实际: " .. tostring(world.screen[1]))
              expect(tostring(world.screen[2]):find("filesystems seen: 1", 1, true) ~= nil,
                     "用例4: 屏幕应报出看到的盘数")
            end

            -- ===== 用例 5：备选路径 /boot/init.lua 也要能被找到 =====
            do
              local disk = { address = "disk-boot", label = "ALT", files = { ["/boot/init.lua"] = OPENOS_INIT, ["/lib/core/boot.lua"] = "-- alt" } }
              local world, component, computer = makeWorld({ disks = { disk } })
              local result, err = runBios(world, component, computer)
              expect(err == nil, "用例5: 不应报错: " .. tostring(err))
              expect(tostring(result):find("booted:disk-boot", 1, true) ~= nil,
                     "用例5: 应能引导 /boot/init.lua，实际: " .. tostring(result))
            end

            -- ===== 用例 6：同一份 BIOS 在 **LuaJ 架构的全局环境**下也要能引导（OC 的默认架构） =====
            -- 为什么单列一条：两种 Lua 架构对 "load 不传环境用哪张表" 的语义不同，
            -- 而盘上的程序能不能看到 component / computer 全看这一点（本闸门第一版就栽在这里）。
            do
              local world, component, computer = makeWorld({ disks = { openosDisk("disk-luaj") } })
              local result, err = runBios(world, component, computer, true)
              expect(err == nil, "用例6: LuaJ 全局环境下不应报错: " .. tostring(err))
              expect(tostring(result):find("booted:disk-luaj", 1, true) ~= nil,
                     "用例6: LuaJ 全局环境下也要能引导盘上的 init.lua，实际: " .. tostring(result))
            end

            -- ===== 用例 7：FATFS 盘上只有 C/RV32 引导记录 /boot/loader.bin =====
            --   用户定案（2026-09-27）：EEPROM 也要能从 FATFS 盘**定位 bootloader**。
            --   Lua 架构下它是 RV32 镜像、根本跑不了 —— 但必须说清楚是"这块盘是给 RV32 的"，
            --   而不是笼统一句"没有 init.lua"（两种情况的修法完全不同）。
            do
              local disk = {
                address = "disk-rv32",
                label = "SYSTEM",
                files = { ["/boot/loader.bin"] = string.rep("x", 2193) },
              }
              local world, component, computer = makeWorld({ disks = { disk } })
              local result, err = runBios(world, component, computer)
              expect(result == nil and err ~= nil, "用例7: 只有引导记录的盘必须报错")
              expect(tostring(err):find("no /init.lua", 1, true) ~= nil,
                     "用例7: 错误信息仍要点名缺 /init.lua，实际: " .. tostring(err))
              expect(tostring(err):find("C/RV32", 1, true) ~= nil,
                     "用例7: 错误信息要点明盘上是 C/RV32 的引导盘，实际: " .. tostring(err))
              expect(tostring(err):find("found 1 C/RV32 boot disk", 1, true) ~= nil,
                     "用例7: 要写清楚找到了几块 C/RV32 引导盘，实际: " .. tostring(err))
              local joined = table.concat(world.screen, " | ")
              expect(joined:find("no bootable", 1, true) ~= nil,
                     "用例7: 屏幕要说明没有可引导的 Lua 入口，实际: " .. joined)
              expect(joined:find("filesystems seen: 1", 1, true) ~= nil,
                     "用例7: 屏幕要报出看到的盘数，实际: " .. joined)
              expect(joined:find("/boot/loader.bin", 1, true) ~= nil,
                     "用例7: 屏幕要报出引导记录的路径，实际: " .. joined)
              expect(joined:find("2193", 1, true) ~= nil,
                     "用例7: 屏幕要报出引导记录的字节数（用 filesystem.size 探到），实际: " .. joined)
              expect(joined:find("this CPU is Lua", 1, true) ~= nil,
                     "用例7: 屏幕要写明这台 CPU 是 Lua、跑不了 RV32 镜像，实际: " .. joined)
              local sawSize = false
              for _, v in ipairs(world.invokes) do
                if v == "disk-rv32:size" then sawSize = true end
              end
              expect(sawSize, "用例7: 定位引导记录必须走 filesystem.size（不是猜）")
            end

            -- ===== 用例 8：双模介质（同一块盘上 /init.lua 与 /boot/loader.bin 都在）=====
            --   这就是"一块盘、两个目标"：Lua 走 /init.lua，C/RV32 走 /boot/loader.bin。
            do
              local disk = openosDisk("disk-both", "SYSTEM")
              disk.files["/boot/loader.bin"] = string.rep("y", 2193)
              local world, component, computer = makeWorld({ disks = { disk } })
              local result, err = runBios(world, component, computer)
              expect(err == nil, "用例8: 双模盘应引导 Lua 入口，实际: " .. tostring(err))
              expect(tostring(result):find("booted:disk-both", 1, true) ~= nil,
                     "用例8: 引导的应是 disk-both，实际: " .. tostring(result))
              expect(world.screen[1] == "booting /init.lua",
                     "用例8: 第一行仍是 Lua 入口，实际: " .. tostring(world.screen[1]))
              local joined = table.concat(world.screen, " | ")
              expect(joined:find("dual-mode", 1, true) ~= nil,
                     "用例8: 屏幕要报出同一块盘还带 C/RV32 引导记录，实际: " .. joined)
              expect(joined:find("2193", 1, true) ~= nil,
                     "用例8: 双模那行要带引导记录的字节数，实际: " .. joined)
            end

            -- ===== 用例 9：/usr/init.lua 也是 Lua 入口候选（第三个候选不是摆设）=====
            do
              local disk = { address = "disk-usr", label = "USR",
                files = { ["/usr/init.lua"] = OPENOS_INIT, ["/lib/core/boot.lua"] = "-- usr" } }
              local world, component, computer = makeWorld({ disks = { disk } })
              local result, err = runBios(world, component, computer)
              expect(err == nil, "用例9: /usr/init.lua 应能引导，实际: " .. tostring(err))
              expect(tostring(result):find("booted:disk-usr", 1, true) ~= nil,
                     "用例9: 应引导 disk-usr，实际: " .. tostring(result))
            end

            -- ===== 用例 10：空盘 + 一块 C/RV32 盘 ⇒ 计数准确、逐盘区分 =====
            do
              local world, component, computer = makeWorld({ disks = {
                { address = "disk-blank", label = "BLANK", files = {} },
                { address = "disk-rv32b", label = "SYSTEM",
                  files = { ["/boot/loader.bin"] = string.rep("z", 100) } }, } })
              local result, err = runBios(world, component, computer)
              expect(result == nil and err ~= nil, "用例10: 两块盘都没有 Lua 入口 ⇒ 必须报错")
              expect(tostring(err):find("found 1 C/RV32 boot disk", 1, true) ~= nil,
                     "用例10: 只应计入真的带引导记录的那一块，实际: " .. tostring(err))
              expect(world.screen[2] == "filesystems seen: 2",
                     "用例10: 盘数要如实（两块都算），实际: " .. tostring(world.screen[2]))
              local joined = table.concat(world.screen, " | ")
              expect(joined:find("disk-rv3", 1, true) ~= nil,
                     "用例10: 逐盘列表要带盘地址前缀，实际: " .. joined)
            end

            -- ===== 用例 11：系统软盘上的真 /init.lua 必须能 load（语法自检）=====
            --   为什么单列一条：旧版第 101 行是 "computer and computer.shutdown and ..." —— 非函数调用的
            --   表达式不能单独作语句，Lua 直接拒绝整块。真机症状：换成 OC 原版 CPU 后
            --   错误=Cryptand Lua BIOS: /init.lua:101: syntax error near 'and'（系统盘在 Lua 架构下一个字都跑不了）。
            do
              local chunk, err = load(INIT, "=/init.lua", "t", setmetatable({}, { __index = _G }))
              expect(chunk ~= nil, "用例11: 真 /init.lua 必须能 load（语法错误会让 Lua 架构整块跑不了），实际: " .. tostring(err))
              expect(#INIT > 200, "用例11: /init.lua 不该是空壳，实际 " .. #INIT .. " 字节")
            end

            -- ===== 用例 12：把真 /init.lua 跑起来（打字 → 回车 → 执行命令 → reboot 退出）=====
            --   覆盖三件事：① 开局打印提示与盘列表；② 键序解码（key_down 的 (char, code) 顺序、
            --   字符码优先 + 键码兜底）真能把 "help" 打进缓冲区并在回车后执行；③ reboot 走 computer.shutdown(true)。
            do
              local world, component, computer = makeWorld({ disks = {
                { address = "disk-openos", label = "OpenOS",
                  files = { ["/init.lua"] = "-- rescue", ["/boot/loader.bin"] = string.rep("y", 2193) } } } })
              computer.shutdown = function(reboot)
                world.shutdown = reboot
              end
              local env = setmetatable({ component = component, computer = computer }, { __index = _G })
              local chunk = assert(load(INIT, "=/init.lua", "t", env), "用例12: /init.lua load 失败")
              local co = coroutine.create(chunk)
              local function step(...)
                local ok, err = coroutine.resume(co, ...)
                expect(ok, "用例12: 协程不应报错，实际: " .. tostring(err))
              end

              step()                                  -- 开局 → 打印提示 + 盘列表 → 停在 prompt
              local opening = table.concat(world.screen, " | ")
              expect(opening:find("Cryptand rescue shell (lua)", 1, true) ~= nil,
                     "用例12: 开局要打印救援 shell 标题，实际: " .. opening)
              expect(opening:find("disk-openos", 1, true) ~= nil,
                     "用例12: 开局要列出机箱里的盘，实际: " .. opening)
              expect(opening:find("/boot/loader.bin", 1, true) ~= nil,
                     "用例12: 盘列表要报出它带来了什么路径，实际: " .. opening)

              -- 敲 "help"：键信号是 (name, address, char, code)。窗口路径的 code 是 GLFW 键码。
              local function typeKey(char, keyCode)
                step("key_down", "kbd-0", char, keyCode)
              end
              typeKey(string.byte("h"), 72)
              typeKey(string.byte("e"), 69)
              typeKey(string.byte("l"), 76)
              typeKey(string.byte("p"), 80)
              local typed = table.concat(world.screen, " | ")
              expect(typed:find("> help", 1, true) ~= nil,
                     "用例12: 键序要回显到输入行（字符码优先解码），实际: " .. typed)
              step("key_down", "kbd-0", 13, 257)       -- 回车（GLFW 257）
              local afterHelp = table.concat(world.screen, " | ")
              expect(afterHelp:find("unknown: help", 1, true) ~= nil,
                     "用例12: 回车要真的把命令交给 shell，实际: " .. afterHelp)

              -- 退格：打错一个字符再退掉（键码路径 = LWJGL2 扫描码 14）
              step("key_down", "kbd-0", string.byte("x"), 88)
              expect(table.concat(world.screen, " | "):find("> x", 1, true) ~= nil,
                     "用例12: 退格前应看到打进去的字符")
              step("key_down", "kbd-0", 0, 14)
              expect(table.concat(world.screen, " | "):find("> x", 1, true) == nil,
                     "用例12: 退格要真的把字符从缓冲区里去掉")
              step("key_down", "kbd-0", 13, 28)        -- 再回车（LWJGL2 扫描码 28）
              expect(table.concat(world.screen, " | "):find("unknown: ", 1, true) ~= nil,
                     "用例12: 扫描码路径的回车也要生效")

              -- text_input 整段文本 + reboot（这一条直接钉住旧版第 101 行那个语法/语义 bug）
              step("text_input", "kbd-0", "reboot" .. string.char(13))   -- 13 = CR（行编辑里的回车）
              expect(world.shutdown == true,
                     "用例12: reboot 必须走 computer.shutdown(true)，实际: " .. tostring(world.shutdown))
              expect(coroutine.status(co) == "dead", "用例12: reboot 之后 shell 应退出，实际: " .. coroutine.status(co))
            end
            RESULT = { passed = passed, failed = failed, failures = failures }
            """;

    public static void main(String[] args) {
        // 与游戏同一份字节：CryptandOcDrivers.luaBios() 也是 Programs.read("bios.lua")
        final String bios = new String(Programs.read("bios.lua"), StandardCharsets.UTF_8);
        if (bios.isEmpty()) {
            System.out.println("[bios-lua] FAIL 读不到 bios.lua 资源（common 的 assets/cryptand/soc/）");
            System.exit(1);
        }

        final Globals globals = JsePlatform.standardGlobals();
        globals.set("__bios_source", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(bios);
            }
        });
        // 系统软盘上的 /init.lua（同一份字节，CryptandOcDrivers 装盘时写的就是它）：
        //   ⚠ 2026-09-27 真机抓到"整个 /init.lua 语法错误 ⇒ Lua 架构换成我们的系统盘一个字都跑不了"，
        //     原因就是此前闸门只跑 bios.lua、没人 load 过这个文件。用例 11/12 专治这一类。
        final String init = new String(Programs.read("init.lua"), StandardCharsets.UTF_8);
        if (init.isEmpty()) {
            System.out.println("[bios-lua] FAIL 读不到 init.lua 资源（common 的 assets/cryptand/soc/）");
            System.exit(1);
        }
        globals.set("__init_source", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(init);
            }
        });
        // 真 userdata：Lua 自己造不出来，而"句柄不是 number"正是这道闸门要复现的关键条件
        globals.set("__make_ud", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.userdataOf(new Object());
            }
        });

        try {
            globals.load(SCRIPT, "=biosLuaSelfTest").call();
        } catch (Exception e) {
            System.out.println("[bios-lua] FAIL 闸门脚本自身出错: " + e);
            e.printStackTrace(System.out);
            System.exit(1);
        }

        final LuaTable result = globals.get("RESULT").checktable();
        final int passed = result.get("passed").checkint();
        final int failed = result.get("failed").checkint();
        for (int i = 1; i <= failed; i++) {
            System.out.println("[bios-lua] FAIL " + result.get("failures").get(i).tojstring());
        }
        System.out.println("[bios-lua] " + passed + "/" + (passed + failed)
                + (failed == 0 ? " —— 全部通过" : " —— 有失败"));
        System.exit(failed == 0 ? 0 : 1);
    }
}
