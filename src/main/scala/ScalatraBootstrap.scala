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

import javax.servlet.ServletContext

import org.mbari.raziel.api.{AuthApi, EndpointsApi, HealthApi}
import org.mbari.raziel.AppConfig
import org.mbari.raziel.services.{
  Annosaurus,
  Charybdis,
  Panoptes,
  VampireSquid,
  VarsKbServer,
  VarsUserServer
}
import org.scalatra.LifeCycle
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import org.mbari.raziel.etc.methanol.HttpClientSupport
import java.lang.System.Logger.Level

class ScalatraBootstrap extends LifeCycle:

  override def init(context: ServletContext): Unit =

    System.getLogger(getClass.getName)
      .log(Level.INFO, s"Mounting ${AppConfig.Name} Servlets")

    // Optional because * is the default
    context.setInitParameter("org.scalatra.cors.allowedOrigins", "*")
    // Disables cookies, but required because browsers will not allow passing credentials to wildcard domains
    context.setInitParameter("org.scalatra.cors.allowCredentials", "false")

    // Methonal needs a Java executor. Everything else a Scala ExeuctionContext
    given executionContext: ExecutionContextExecutor = ExecutionContext.global

    val annosaurus     = Annosaurus.default
    val charybdis      = Charybdis.default
    val panoptes       = Panoptes.default
    val vampireSquid   = VampireSquid.default
    val varsKbServer   = VarsKbServer.default
    val varsUserServer = VarsUserServer.default
    val healthServices =
      Seq(annosaurus, charybdis, panoptes, vampireSquid, varsKbServer, varsUserServer)

    context.mount(AuthApi(varsUserServer), "/auth")
    context.mount(EndpointsApi(), "/endpoints")
    context.mount(HealthApi(healthServices), "/health")
