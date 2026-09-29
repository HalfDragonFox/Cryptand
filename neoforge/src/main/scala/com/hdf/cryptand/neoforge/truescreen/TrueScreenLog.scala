package com.hdf.cryptand.neoforge.truescreen

/**
 * 移植件的统一日志出口。
 *
 * OC 原本用 `li.cil.oc.OpenComputers.log`（一个全局 log4j logger）。我们不复用 OC 的日志对象
 * （那会把我们的日志混进 OC 的命名空间里，排查时分不清是谁打的），改用 cryptand 自己的 logger
 * ——与 `CryptandOcDrivers` 等既有代码同名，日志一律进 `run/logs/latest.log`。
 */
object TrueScreenLog {
  final val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("cryptand/truescreen")
}
