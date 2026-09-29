/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/component/package.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common

import scala.language.implicitConversions

package object component {
  implicit def result(args: Any*): Array[AnyRef] =
    com.hdf.cryptand.neoforge.truescreen.util.ResultWrapper.result(args: _*)
}
