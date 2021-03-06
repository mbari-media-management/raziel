/*
 * Copyright 2021 MBARI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mbari.raziel.api

import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.JWT
import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import java.time.Instant
import java.util.concurrent.Executor
import java.util.Date
import org.mbari.raziel.AppConfig
import org.mbari.raziel.domain.{BasicAuth, BearerAuth, ErrorMsg, JwtAuthPayload}
import org.mbari.raziel.domain.Auth
import org.mbari.raziel.etc.auth0.JwtHelper
import org.mbari.raziel.etc.circe.CirceCodecs.{given, _}
import org.mbari.raziel.services.VarsUserServer
import org.scalatra.{FutureSupport, InternalServerError, Ok, ScalatraServlet, Unauthorized}
import org.scalatra.ActionResult
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.given
import scala.util.{Failure, Success, Try}
import zio.*

/**
 * Provides endpoints for authentication and authorization.
 *
 * ## /auth
 *
 * ### Request
 *
 * ```text
 * POST /auth
 * Authorization: Basic base64(username:password)
 * ```
 *
 * ### Response 200
 *
 * ```text
 * HTTP/1.1 200 OK
 * Connection: close
 * Date: Mon, 22 Nov 2021 22:53:47 GMT
 * Content-Type: application/json;charset=utf-8
 * Content-Length: 338
 *
 * {
 * "tokenType": "Bearer",
 * "accessToken": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzUxMiJ9.eyJhZmZpbGlhdGlvbiI6Ik1CQVJJIiwiaXNzIjoiaHR0cDovL3d3dy5tYmFyaS5vcmciLCJleHAiOjE2NTMxNzM2MjcsImlhdCI6MTYzNzYyMTYyNywiZW1haWwiOiJicmlhbkBtYmFyaS5vcmciLCJ1c2VybmFtZSI6ImJyaWFuIn0.FuGr9NoQjbrHKPUPvRmscmGjKWYkTfsqNcgnAbrZDvnGpq0gv31kz5qFAY6Ve5KQUouAttlh0aU5ny-pqxOrCg"
 * }
 * ```
 *
 * ### RESPONSE 401
 *
 * ```text
 * HTTP/1.1 401 Unauthorized
 * Connection: close
 * Date: Mon, 22 Nov 2021 22:56:29 GMT
 * Content-Type: application/json;charset=utf-8
 * Content-Length: 52
 *
 * {
 * "message": "Invalid credentials",
 * "responseCode": 401
 * }
 * ```
 *
 * ## /auth/verify
 *
 * ### Request
 *
 * ```text
 * POST /auth/verify
 * Authorization: Bearer <JWT>
 * ```
 *
 * ### RESPONSE 200
 *
 * ```text
 * HTTP/1.1 200 OK
 * Connection: close
 * Date: Mon, 22 Nov 2021 22:55:27 GMT
 * Content-Type: application/json;charset=utf-8
 * Content-Length: 97
 *
 * {
 * "username": "brian",
 * "iss": "http://www.mbari.org",
 * "email": "brian@mbari.org",
 * "affiliation": "MBARI"
 * }
 * ```
 *
 * ### RESPONSE 401
 *
 * ```text
 * HTTP/1.1 401 Unauthorized
 * Connection: close
 * Date: Mon, 22 Nov 2021 22:55:50 GMT
 * Content-Type: application/json;charset=utf-8
 * Content-Length: 52
 *
 * {
 * "message": "Invalid credentials",
 * "responseCode": 401
 * }
 * ```
 *
 * @param varsUserServer
 *   Needed for authorization
 * @author
 *   Brian Schlining
 * @since 2021-11-23T11:00:00
 */
class AuthApi(varsUserServer: VarsUserServer) extends ScalatraServlet:

  private val jwtHelper = JwtHelper.default
  private val runtime   = zio.Runtime.default

  after() {
    contentType = "application/json"
  }


  post("/") {
    Option(request.getHeader("X-Api-Key")) match 
      case Some(key) =>
        if (key == AppConfig.MasterKey)
          val token = jwtHelper.createJwt(Map("username" -> "master"))
          BearerAuth(token).stringify
        else
          halt(Unauthorized(ErrorMsg("Invalid credentials", 401).stringify))
      case None =>
          
        val auth = Option(request.getHeader("Authorization"))
          .flatMap(a => BasicAuth.parse(a))
          .toRight(new IllegalArgumentException("Authorization header required"))

        val app = for
          a       <- IO.fromEither(auth)
          // _  <- Task.succeed(log.info(s"auth: $a"))
          u       <- varsUserServer.Users.findByName(a.username)
          // _  <- Task.succeed(log.info(s"user: $u"))
          ok      <- Task.succeed(u.map(v => v.authenticate(a.password)).getOrElse(false))
          payload <- Task.succeed(
                      if (ok)
                        Some(JwtAuthPayload.fromUser(u.get))
                      else
                        None
                    )
        yield payload

        Try(runtime.unsafeRun(app)) match
          case Success(payload) =>
            payload match
              case Some(p) =>
                val token = jwtHelper.createJwt(p.asMap())
                BearerAuth(token).stringify
              case None    =>
                halt(Unauthorized(ErrorMsg("Invalid credentials", 401).stringify))
          case Failure(e)       =>
            halt(InternalServerError(ErrorMsg(e.getMessage, 401).stringify))

  }



  post("/verify") {

    val auth = Option(request.getHeader("Authorization"))
      .flatMap(a => BearerAuth.parse(a))
      .toRight(new IllegalArgumentException("Authorization header required"))

    val either = for
      a          <- auth
      decodedJwt <- jwtHelper.verifyJwt(a.accessToken)
    yield decodedJwt

    either match
      case Right(jwt) =>
        jwt
          .getClaims
          .asScala
          .toMap
          .filter((key, claim) => claim.asString != null && claim.asString.nonEmpty)
          .map((key, claim) => (key, claim.asString()))
          .stringify

      case Left(e) =>
        halt(Unauthorized(ErrorMsg(s"Invalid credentials: ${e.getClass}", 401).stringify))

  }
