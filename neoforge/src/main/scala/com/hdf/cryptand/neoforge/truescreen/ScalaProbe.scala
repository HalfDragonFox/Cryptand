/*
 * Cryptand — Scala 编译链探针（2026-09-27）
 *
 * 目的：证明 neoforge 模块的 Scala 编译链可用（这是「真彩屏全套移植 OC 屏幕代码（Scala）」
 * 的前置条件）。移植完成后本文件删除（它不属于任何 OC 源文件，无需保留版权头）。
 *
 * 运行期 scala 提供者：OC 的语言加载器 kotori_scala（Scalable Cat's Force 3.3.3-build-15，
 * FMLModType=LIBRARY），自带 scala-library 2.13.12 —— 与编译期用的那一份一致。
 */
package com.hdf.cryptand.neoforge.truescreen

object ScalaProbe {

  /** 返回一个常量（供断言/日志用）。 */
  def probe(): String = "cryptand-scala-ok"

  /** 标准入口：证明 Scala 编译产物能在真实 scala 运行时上跑起来（离线闸门用）。
    * 用法：java -cp <scalable-cats-force jar>;<neoforge classes 目录> \
    *            com.hdf.cryptand.neoforge.truescreen.ScalaProbe
    */
  def main(args: Array[String]): Unit = {
    println("[Cryptand] ScalaProbe: scala compile chain ready, probe=" + probe())
  }
}
