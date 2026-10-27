/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.texera.amber.operator

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** The `texera-support` package against the helpers the translator inlines. */
class StandaloneHelpersSpec extends AnyFlatSpec with Matchers {

  private val module: Path = Path
    .of(sys.env.getOrElse("TEXERA_HOME", "."))
    .resolve("texera-support")
    .resolve("texera_support")
    .resolve("__init__.py")

  // The file's leading comment lines and the blank line after them.
  private def licenceHeader(text: String): String = {
    val lines = text.linesWithSeparators.toSeq
    val header = lines.takeWhile(_.startsWith("#"))
    (header ++ lines.drop(header.length).takeWhile(_.trim.isEmpty)).mkString
  }

  // With TEXERA_SUPPORT_WRITE set, the module is rewritten instead of checked.
  "texera_support/__init__.py" should "hold exactly the helpers the translator inlines" in {
    val text = new String(Files.readAllBytes(module), StandardCharsets.UTF_8)
    val header = licenceHeader(text)
    if (sys.env.contains("TEXERA_SUPPORT_WRITE")) {
      Files.write(
        module,
        (header + StandaloneHelpers.packageModule).getBytes(StandardCharsets.UTF_8)
      )
    }
    withClue(s"$module is stale; rerun this spec with TEXERA_SUPPORT_WRITE=1 to rewrite it.\n") {
      new String(Files.readAllBytes(module), StandardCharsets.UTF_8)
        .drop(header.length) shouldBe StandaloneHelpers.packageModule
    }
  }
}
