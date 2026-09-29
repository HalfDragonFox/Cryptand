--[[
  Cryptand Lua 引导器（系统软盘上的 /init.lua）

  背景（2026-09-26 用户截图）：机器换用 OC 原版 CPU 后是 **Lua 架构**，它读 EEPROM 里的
  Cryptand Lua BIOS；BIOS 会在盘上找 /init.lua。系统软盘此前只带了 C 版系统
  （/boot/system.bin，RV32 机器码），Lua 架构跑不了，于是 BIOS 只能报"没有 init.lua"。

  这个文件就是那块软盘在 **Lua 架构**下能跑的东西：一个自包含的极简交互环境。
  它不假装自己是完整的 OS —— 只提供"看得见、动得了"的最小能力，并把"这台机器为什么
  跑不了 C 版系统"讲清楚。

  ⚠ 只用 OC 在 BIOS 环境里保证存在的全局（component / computer），
    键盘与屏幕交互全部自己用组件调用实现（不依赖 OpenOS 的 read/write）。

  ⚠ 2026-09-27 修两个真机抓到的 bug（离线闸门 runBiosLuaTest 用例 11/12 钉住）：
    ① 旧版第 101 行写成 "computer and computer.shutdown and computer.shutdown(true)" —— Lua 的
       表达式不能单独作语句（只有函数调用可以）⇒ **整个 /init.lua 语法错误、根本 load 不了**：
       真机症状 = 换成 OC 原版 CPU 后 错误=Cryptand Lua BIOS: /init.lua:101: syntax error near 'and'。
    ② 键盘解码取错了信号字段：OC 把信号 resume 进协程时是 (name, address, char, code)，
       而旧版写的是 local _, _, code, ch = coroutine.yield()（把字符当成键码、把键码当成字符）
       ⇒ 即使能 load，敲键也只会一个字都进不了缓冲区、回车永远不触发。
       现在按 **字符码优先、键码兜底** 解码（窗口路径给 GLFW 键码、方块键盘路径给 LWJGL2 扫描码，
       两套约定都要能进回车/退格 —— 靠查表猜键码会在其中一条路上失灵）。
]]

local component = component

-- ==================== 屏幕 / 键盘（自己实现，不依赖 OpenOS） ====================

local gpuAddr = component.list("gpu")()
local screenAddr = component.list("screen")()

local function gpu(method, ...)
  if gpuAddr then
    return component.invoke(gpuAddr, method, ...)
  end
  return nil
end

if gpuAddr and screenAddr then
  gpu("bind", screenAddr)
end

local row = 1
local function print(line)
  if not gpuAddr then
    return
  end
  gpu("set", 1, row, tostring(line))
  row = row + 1
  if row > 24 then
    gpu("fill", 1, 1, 80, 24, " ")
    row = 1
  end
end

-- 回车 / 退格：**字符码优先、键码兜底**（见文件头 ② 的说明）
local function isEnter(charCode, keyCode)
  return charCode == 13 or charCode == 10
      or keyCode == 257 or keyCode == 28    -- GLFW_KEY_ENTER / LWJGL2 扫描码 28
end

local function isBackspace(charCode, keyCode)
  return charCode == 8 or charCode == 127
      or keyCode == 259 or keyCode == 14    -- GLFW_KEY_BACKSPACE / LWJGL2 扫描码 14
end

-- 读一行：**不要求存在 keyboard 组件**。
--   为什么不要求：Lua 架构下键盘是**信号**（我们的真彩屏/OC 屏以 key_down、text_input 发出来），
--   进程收信号不需要"键盘组件"这一层。旧版先 component.list("keyboard")()，拿不到就每条命令都
--   打印 "unknown: nil" 空转到天荒地老 —— 那是把"没有键盘组件"误当成"没有输入"。
local function readLine()
  local buf = ""
  while true do
    gpu("set", 1, row, "> " .. buf)
    -- OC 把信号按 (name, address, ...) 的顺序 resume 进协程
    local name, _, a, b = coroutine.yield()
    if name == "text_input" then
      local text = tostring(a or "")
      for i = 1, #text do
        local c = text:sub(i, i)
        if c == "\r" or c == "\n" then
          return buf
        elseif c == "\b" then
          buf = buf:sub(1, -2)
        else
          buf = buf .. c
        end
      end
    elseif name == "key_down" then
      local charCode = tonumber(a) or 0
      local keyCode = tonumber(b) or 0
      if isEnter(charCode, keyCode) then
        return buf
      elseif isBackspace(charCode, keyCode) then
        buf = buf:sub(1, -2)
      elseif charCode >= 32 and charCode <= 126 then
        buf = buf .. string.char(charCode)
      end
    end
  end
end

-- ==================== 极简 shell ====================

local function listDisks()
  local n = 0
  for address, kind in component.list("filesystem") do
    n = n + 1
    print(string.format("[%d] %s", n, address))
    for _, path in ipairs({ "/init.lua", "/boot/system.bin", "/boot/loader.bin" }) do
      local ok, exists = pcall(component.invoke, address, "exists", path)
      if ok and exists then
        print("      " .. path)
      end
    end
  end
  if n == 0 then
    print("(no filesystem in this machine)")
  end
  return n
end

print("Cryptand rescue shell (lua)")
print("this floppy's system is the C/RV32 build;")
print("this machine is running the OC Lua architecture.")
print("")
listDisks()
print("")
print("commands: disks | reboot")

while true do
  local line = readLine()
  if line == "disks" then
    print("")
    listDisks()
  elseif line == "reboot" or line == "exit" then
    if type(computer) == "table" and computer.shutdown then
      computer.shutdown(true)
    end
    return
  else
    print("unknown: " .. tostring(line))
  end
end
