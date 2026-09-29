--[[
  Cryptand Lua BIOS —— Lua 架构的 EEPROM 里跑的就是这段 Lua。

  用户定案 2026-09-27："EEPROM 既支持原版 OC 的 Lua 兼容启动，也支持从 FATFS 盘找 bootloader 启动"。
  它是 **Lua 目标的执行者**（与 C/RV32 目标并列，按架构选）：同一块盘、同一套选盘顺序，差别只在
  "把哪一层交给 CPU" —— C/RV32 交给盘上的 /boot/loader.bin（RV32 镜像，宿主 BIOS 刷 guest ROM），
  Lua 交给盘上的 /init.lua（本文解释执行）。

  职责：① 把 computer.getBootAddress/setBootAddress 挂给系统（存储 = eeprom 的 data 段，与官方同）；
  ② 找 Lua 入口并运行；③ 找不到时逐盘列出来，并探 /boot/loader.bin 区分"空盘"与"这块盘是给 C/RV32 的"
  （两种情况的修法完全不同）；④ 报错不静默（屏幕 + Lua error 双通道）。

  ⚠ 同一张表：Lua 入口候选 = Java 侧 BootPlan.LUA_INIT_PATHS，引导记录路径 = Programs.LOADER_PATH
    （Lua 不能 import Java，靠离线闸门 pin 住：:common:runBiosLuaTest / :common:runBootPlanTest）。
  ⚠ FATFS/Cryptand 盘在 Lua 侧就是普通 filesystem 组件（CryptandOcDrivers.mountDisk 挂的），
    所以"在 FATFS 盘上定位入口"只有一条通道：组件 API（不猜盘上是什么文件系统）。
]]

local component = component

local CANDIDATES = { "/init.lua", "/boot/init.lua", "/usr/init.lua" }
local LOADER_PATH = "/boot/loader.bin"

-- ==================== 屏幕（有就画，没有就沉默） ====================
local gpuAddr = component.list("gpu")()
local screenAddr = component.list("screen")()
local row = 1

local function say(text)
  if not (gpuAddr and screenAddr) then
    return
  end
  component.invoke(gpuAddr, "bind", screenAddr)
  component.invoke(gpuAddr, "set", 1, row, tostring(text))
  row = row + 1
end

-- ==================== 引导盘地址 ====================
-- computer.getBootAddress/setBootAddress 是 **BIOS 的职责**（machine.lua 不提供）：真 OpenOS 的
-- /init.lua 第 2 行就读它。存储与官方一致 = eeprom 的 data 段。
local eepromAddr = component.list("eeprom")()

computer.getBootAddress = function()
  return component.invoke(eepromAddr, "getData")
end

computer.setBootAddress = function(address)
  component.invoke(eepromAddr, "setData", address or "")
end

-- ==================== 盘与两个入口 ====================
-- ① Lua 入口（本目标）：CANDIDATES。 ② C/RV32 引导记录（另一个目标）：LOADER_PATH，只在定位失败时探它。

-- list 第二参 true = 含不可见组件（机箱内部的盘只列邻居会看不见）；
-- OC 的 filesystem.open 返回 **userdata 句柄**（不是 number），判据只能看 nil。
local function filesystems()
  local out = {}
  for address in component.list("filesystem", true) do
    out[#out + 1] = address
  end
  return out
end

local function exists(address, path)
  local ok, hit = pcall(component.invoke, address, "exists", path)
  return ok and hit or false
end

local function readAll(address, path)
  local ok, handle = pcall(component.invoke, address, "open", path, "r")
  if not ok or handle == nil then
    return nil
  end
  local parts = {}
  while true do
    local chunk = component.invoke(address, "read", handle, 1024)
    if type(chunk) ~= "string" or #chunk == 0 then
      break
    end
    parts[#parts + 1] = chunk
  end
  component.invoke(address, "close", handle)
  return table.concat(parts)
end

-- C/RV32 引导记录的字节数：nil = 没有；-1 = 在但拿不到大小（不假装知道）
local function loaderBytes(address)
  if not exists(address, LOADER_PATH) then
    return nil
  end
  local ok, n = pcall(component.invoke, address, "size", LOADER_PATH)
  if ok and type(n) == "number" then
    return n
  end
  return -1
end

local function loaderText(bytes)
  if bytes == nil then
    return ""
  end
  if bytes < 0 then
    return "  " .. LOADER_PATH .. " (size unknown)"
  end
  return "  " .. LOADER_PATH .. " = " .. tostring(bytes) .. " bytes"
end

local function tryDisk(address)
  for _, path in ipairs(CANDIDATES) do
    if exists(address, path) then
      local source = readAll(address, path)
      if source and #source > 0 then
        return source, path
      end
    end
  end
  return nil
end

-- ==================== 选盘并引导 ====================
-- 先试 EEPROM 记住的盘，再全盘扫描（记住的盘被拔走也不能卡住）。
-- ⚠ 顺序说明（诚实）：Lua 侧看不见"软盘/硬盘"（组件没这个属性），扫描顺序 = OC 组件注册顺序（≈槽位序）；
--   Java 侧 BootPlan.ordered 是"软盘优先 → 槽位升序"。两侧"这块盘能不能引导"的结论一致，
--   多块盘同时可引导时先选谁可能不同；"记忆优先"两侧都有。
local source, path, address
local remembered = computer.getBootAddress()
if type(remembered) == "string" and #remembered > 0 then
  source, path = tryDisk(remembered)
  if source then
    address = remembered
  end
end
local disks = filesystems()
if not source then
  for _, candidate in ipairs(disks) do
    source, path = tryDisk(candidate)
    if source then
      address = candidate
      break
    end
  end
end

if not source then
  say("no bootable Lua entry found")
  say("filesystems seen: " .. tostring(#disks))
  local rv32 = {}
  for i, candidate in ipairs(disks) do
    local ok, label = pcall(component.invoke, candidate, "getLabel")
    local bytes = loaderBytes(candidate)
    if bytes ~= nil then
      rv32[#rv32 + 1] = candidate
    end
    say(string.format("  [%d] %s %s%s", i, candidate:sub(1, 8), ok and label or "", loaderText(bytes)))
  end
  if #rv32 > 0 then
    -- "从 FATFS 盘找 bootloader"在 Lua 侧的真实结论：定位得到，但 RV32 镜像不能在 Lua CPU 上执行
    say("this CPU is Lua: " .. LOADER_PATH .. " is a C/RV32 image, cannot run here")
    say("install a Lua entry " .. CANDIDATES[1] .. ", or use a Cryptand RV32 CPU")
    error("Cryptand Lua BIOS: no /init.lua on any filesystem (found " .. tostring(#rv32)
      .. " C/RV32 boot disk(s) with " .. LOADER_PATH .. ")", 0)
  end
  error("Cryptand Lua BIOS: no /init.lua on any filesystem", 0)
end

-- 把选定的盘写进 EEPROM（OpenOS 的 /init.lua 会用 getBootAddress 拿它）；地址没变就不写
if remembered ~= address then
  computer.setBootAddress(address)
end

say("booting " .. path)

-- 双模介质：同一块盘还带 C/RV32 引导记录（一块盘、两个目标）
local bytes = loaderBytes(address)
if bytes ~= nil then
  say("dual-mode medium:" .. loaderText(bytes))
end

-- ⚠ 显式把"BIOS 自己看到的环境"传给盘上的程序：两种 Lua 架构对"load 不传环境时用哪张表"语义不同
--   （LuaJ 会落到没有 component/computer 的 Globals）⇒ 显式传与环境实现无关。
local BIOS_ENV = _ENV or _G
local chunk, reason = load(source, "=" .. path, "t", BIOS_ENV)
if not chunk then
  say("bios load failed: " .. tostring(reason))
  error("Cryptand Lua BIOS: " .. tostring(reason), 0)
end

return chunk()
