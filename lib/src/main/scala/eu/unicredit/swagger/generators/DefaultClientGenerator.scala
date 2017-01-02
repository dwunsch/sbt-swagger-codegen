/* Copyright 2015 UniCredit S.p.A.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.unicredit.swagger.generators

import treehugger.forest._
import definitions._
import treehuggerDSL._
import eu.unicredit.swagger.StringUtils._

import io.swagger.parser.SwaggerParser
import io.swagger.models._
import io.swagger.models.parameters._
import scala.collection.JavaConversions._

class DefaultClientGenerator extends ClientGenerator with SharedServerClientCode {

  def clientNameFromFileName(fn: String) = objectNameFromFileName(fn, "Client")

  def generate(fileName: String, packageName: String): Iterable[SyntaxString] = {
    val swagger = new SwaggerParser().read(fileName)

    val basePath = Option(swagger.getBasePath).getOrElse("/")

    val clientPackageName =
      packageName + ".client"

    val clientName =
      clientNameFromFileName(fileName)

    val completePaths =
      swagger.getPaths.keySet().toSeq

    def composeClient(p: String): Seq[Tree] = {
      val path = swagger.getPath(p)
      if (path == null) return Seq()

      val ops: Seq[(String, Operation)] =
        Seq(Option(path.getDelete) map ("DELETE" -> _),
            Option(path.getGet) map ("GET" -> _),
            Option(path.getPost) map ("POST" -> _),
            Option(path.getPut) map ("PUT" -> _)).flatten

      for {
        op <- ops
      } yield {
        val (httpVerb, swaggerOp) = op

        val methodName =
          if (op._2.getOperationId != null) op._2.getOperationId
          else throw new Exception("Please provide an operationId in: " + p)

        val okRespType: (String, Option[Type]) =
          getOkRespType(swaggerOp) getOrElse {
            throw new Exception(s"cannot determine Ok result type for $methodName")
          }

        val opType =
          op._1.toLowerCase

        val url =
          doUrl(basePath, p)

        val methodCall =
          genClientMethod(methodName, url, opType, op._2.getParameters, okRespType)

        methodCall
      }
    }

    val imports =
      BLOCK {
        Seq(
          IMPORT(packageName, "_"),
          IMPORT(packageName + ".json", "_"),
          IMPORT("play.api.libs.ws", "_"),
          IMPORT("play.api.libs.json", "_"),
          IMPORT("javax.inject", "_"),
          IMPORT("play.api.libs.concurrent.Execution.Implicits", "_")
        )
      } inPackage clientPackageName

    val classDef = CLASSDEF(clientName).empty
    val params1 = "@Inject() (WS: WSClient)"
    val params2 = (CLASSDEF("") withParams PARAM("baseUrl", StringClass)).empty

    val RENDER_URL_PARAMS: Tree =
      DEFINFER("_render_url_params") withFlags Flags.PRIVATE withParams PARAM(
        "pairs",
        TYPE_*(TYPE_TUPLE(StringClass, OptionClass TYPE_OF AnyClass))) := BLOCK(
        Seq(
          VAL("parts") := (
            REF("pairs")
              DOT "collect" APPLY BLOCK(
              CASE(TUPLE(ID("k"), REF("Some") UNAPPLY ID("v"))) ==> (REF("k") INFIX ("+", LIT("=")) INFIX ("+", REF(
                "v"))))
          ),
          IF(REF("parts") DOT "nonEmpty")
            THEN (
              REF("parts") DOT "mkString" APPLY (LIT("?"), LIT("&"), LIT(""))
            )
            ELSE LIT("")
        ))

    val RENDER_HEADER_PARAMS: Tree =
      DEFINFER("_render_header_params") withFlags Flags.PRIVATE withParams PARAM(
        "pairs",
        TYPE_*(TYPE_TUPLE(StringClass, OptionClass TYPE_OF AnyClass))) := BLOCK(
        Seq(
          REF("pairs")
            DOT "collect" APPLY BLOCK(CASE(TUPLE(ID("k"), REF("Some") UNAPPLY ID("v"))) ==>
            (REF("k") INFIX ("->", REF("v") DOT "toString")))
        ))

    val body = BLOCK {
      completePaths.flatMap(composeClient) :+
        RENDER_URL_PARAMS :+ RENDER_HEADER_PARAMS
    }

    Seq(
      SyntaxString(clientName,
                   treeToString(imports),
                   treeToString(classDef) + " " + params1 + treeToString(params2)
                     .replace("class", "") + " " + treeToString(body)))
  }

  def genClientMethod(methodName: String,
                      url: String,
                      opType: String,
                      params: Seq[Parameter],
                      respType: (String, Option[Type])): Tree = {
    val bodyParams = getPlainParamsFromBody(params)

    val fullBodyParams = getParamsToBody(params)

    val methodParams = getMethodParamas(params)

    //probably to be fixed with a custom ordering
    val urlParams: Seq[Tree] =
      params collect {
        case query: QueryParameter =>
          val name = query.getName
          LIT(name) INFIX ("->",
          if (query.getRequired) REF("Some") APPLY REF(name)
          else REF(name))
      }

    val RuntimeExceptionClass =
      definitions.getClass("java.lang.RuntimeException")

    val headerParams: Seq[Tree] =
      params collect {
        case param: HeaderParameter =>
          val name = param.getName
          LIT(name) INFIX ("->",
          if (param.getRequired) REF("Some") APPLY REF(name)
          else REF(name))
      }

    val baseUrl =
      INTERP("s", LIT(cleanDuplicateSlash("$baseUrl/" + cleanPathParams(url))))
    val baseUrlWithParams =
      if (urlParams.isEmpty)
        baseUrl
      else
        baseUrl INFIX ("+", THIS DOT "_render_url_params" APPLY (urlParams: _*))

    val wsUrl = REF("WS") DOT "url" APPLY baseUrlWithParams
    val wsUrlWithHeaders =
      if (headerParams.isEmpty)
        wsUrl
      else
        wsUrl DOT "withHeaders" APPLY SEQARG(THIS DOT "_render_header_params" APPLY (headerParams: _*))

    val tree: Tree =
      DEFINFER(methodName) withParams (methodParams.values ++ bodyParams.values) := BLOCK(
        wsUrlWithHeaders DOT opType APPLY fullBodyParams.values DOT "map" APPLY (
          LAMBDA(PARAM("resp")) ==> BLOCK {
            Seq(
              IF(
                INFIX_CHAIN(
                  "&&",
                  PAREN(REF("resp") DOT "status" INFIX (">=", LIT(200))),
                  PAREN(REF("resp") DOT "status" INFIX ("<=", LIT(299)))
                )
              ).THEN(
                  respType._2.map { typ =>
                    {
                      REF("Json") DOT "parse" APPLY (REF("resp") DOT "body") DOT
                        "as" APPLYTYPE typ
                    }
                  }.getOrElse(REF("Unit"))
                )
                .ELSE(
                  THROW(RuntimeExceptionClass,
                        INFIX_CHAIN("+",
                                    LIT("unexpected response status: "),
                                    REF("resp") DOT "status",
                                    LIT(" "),
                                    REF("resp") DOT "body"))
                )
            )
          }
        ))

    tree
  }

  def getParamsToBody(params: Seq[Parameter]): Map[String, Tree] =
    params.collect {
      case bp: BodyParameter =>
        val tree = REF("Json") DOT "toJson" APPLY REF(bp.getName)
        bp.getName -> tree
    }.toMap

  def getPlainParamsFromBody(params: Seq[Parameter]): Map[String, ValDef] =
    params.collect {
      case bp: BodyParameter =>
        val tree: ValDef = PARAM(bp.getName, noOptParamType(bp))
        bp.getName -> tree
    }.toMap
}
