/****************************************************************
 * Copyright © 2026 Shuwari Africa Ltd.                         *
 *                                                              *
 * This file is licensed to you under the terms of the Apache   *
 * License Version 2.0 (the "License"); you may not use this    *
 * file except in compliance with the License. You may obtain   *
 * a copy of the License at:                                    *
 *                                                              *
 *     https://www.apache.org/licenses/LICENSE-2.0              *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, *
 * either express or implied. See the License for the specific  *
 * language governing permissions and limitations under the     *
 * License.                                                     *
 ****************************************************************/
package snx.sbt

import snx.NativeRuntime

/** How a [[NativeLibrary]] binds into a binary: linked `Static` (baked in) or `Dynamic` (resolved at runtime). See
  * [[Linkage$ Linkage]].
  */
enum Linkage derives CanEqual:
  case Static, Dynamic

/** The single-value lift for [[Linkage]]. */
object Linkage:

  /** Lift a [[Linkage]] to a constant per-platform selector. */
  given Conversion[Linkage, PartialFunction[NativeRuntime, Linkage]] = linkage => { case _ => linkage }
