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

import java.nio.file.Files
import java.nio.file.Path

import snx.Arch
import snx.OS
import snx.TargetPlatform

class ToolchainSuite extends munit.FunSuite:

  private val windows = TargetPlatform(OS.Windows, Arch.X86_64)
  private val windowsArm = TargetPlatform(OS.Windows, Arch.Aarch64)

  private def visualStudio(hostDirectories: String*): Path =
    val vc = Files.createTempDirectory("snx-vc").nn
    hostDirectories.foreach { host =>
      val bin = Files.createDirectories(vc.resolve(s"Tools/Llvm/$host/bin")).nn
      Seq("clang.exe", "clang++.exe").foreach(name => Files.createFile(bin.resolve(name)))
    }
    vc

  test("a Visual Studio developer environment supplies its own Clang for the host's architecture"):
    val vc = visualStudio("x64", "ARM64")
    val env = Map("VCINSTALLDIR" -> vc.toString)
    try
      assertEquals(SNXPlugin.visualStudioClang(env, windows, "clang.exe"), Some(vc.resolve("Tools/Llvm/x64/bin/clang.exe")))
      assertEquals(SNXPlugin.visualStudioClang(env, windowsArm, "clang++.exe"), Some(vc.resolve("Tools/Llvm/ARM64/bin/clang++.exe")))
    finally sbt.io.IO.delete(vc.toFile.nn)

  test("LLVM_BIN, an absent environment, a missing Clang and a non-Windows host each leave discovery to Scala Native"):
    val vc = visualStudio("ARM64")
    val env = Map("VCINSTALLDIR" -> vc.toString)
    try
      assertEquals(SNXPlugin.visualStudioClang(env + ("LLVM_BIN" -> "C:/llvm/bin"), windowsArm, "clang.exe"), None)
      assertEquals(SNXPlugin.visualStudioClang(Map.empty, windows, "clang.exe"), None)
      assertEquals(SNXPlugin.visualStudioClang(env, windows, "clang.exe"), None)
      Seq(OS.Linux, OS.Darwin).foreach { os =>
        assertEquals(SNXPlugin.visualStudioClang(env, TargetPlatform(os, Arch.Aarch64), "clang.exe"), None, os.token)
      }
    finally sbt.io.IO.delete(vc.toFile.nn)
end ToolchainSuite
