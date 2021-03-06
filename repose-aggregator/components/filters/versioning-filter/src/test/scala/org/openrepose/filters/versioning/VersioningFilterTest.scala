/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package org.openrepose.filters.versioning

import java.net.URL
import java.util.concurrent.TimeUnit
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import org.mockito.ArgumentCaptor
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.openrepose.commons.config.manager.UpdateListener
import org.openrepose.commons.utils.http.CommonHttpHeader
import org.openrepose.commons.utils.http.media.MimeType
import org.openrepose.core.services.config.ConfigurationService
import org.openrepose.core.services.healthcheck.{HealthCheckService, HealthCheckServiceProxy, Severity}
import org.openrepose.core.services.reporting.metrics.{MeterByCategory, MetricsService}
import org.openrepose.core.systemmodel._
import org.openrepose.filters.versioning.config.{MediaType, MediaTypeList, ServiceVersionMapping, ServiceVersionMappingList}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, FunSpec, Matchers}
import org.springframework.mock.web.{MockFilterChain, MockFilterConfig, MockHttpServletRequest, MockHttpServletResponse}

class VersioningFilterTest extends FunSpec with Matchers with BeforeAndAfterEach with MockitoSugar {

  val systemModel = new SystemModel()
  val cluster = new ReposeCluster()
  val nodeList = new NodeList()
  val node = new Node()
  val destinationList = new DestinationList()
  val endpoint = new DestinationEndpoint()
  endpoint.setId("endpoint")
  endpoint.setDefault(true)
  destinationList.getEndpoint.add(endpoint)
  node.setId("node")
  node.setHostname("localhost")
  nodeList.getNode.add(node)
  cluster.setId("cluster")
  cluster.setNodes(nodeList)
  cluster.setDestinations(destinationList)
  systemModel.getReposeCluster.add(cluster)

  var configurationService: ConfigurationService = _
  var healthCheckService: HealthCheckService = _
  var healthCheckServiceProxy: HealthCheckServiceProxy = _
  var metricsService: MetricsService = _
  var meterByCategory: MeterByCategory = _
  var request: MockHttpServletRequest = _
  var response: MockHttpServletResponse = _
  var filterChain: MockFilterChain = _
  var filter: VersioningFilter = _
  var systemModelListener: UpdateListener[SystemModel] = _
  var versioningListener: UpdateListener[ServiceVersionMappingList] = _

  override def beforeEach() = {
    configurationService = mock[ConfigurationService]
    healthCheckService = mock[HealthCheckService]
    healthCheckServiceProxy = mock[HealthCheckServiceProxy]
    metricsService = mock[MetricsService]
    meterByCategory = mock[MeterByCategory]

    request = new MockHttpServletRequest()
    response = new MockHttpServletResponse()
    filterChain = new MockFilterChain()

    when(healthCheckService.register()).thenReturn(healthCheckServiceProxy)
    when(metricsService.newMeterByCategory(any[Class[_]], anyString(), anyString(), any[TimeUnit])).thenReturn(meterByCategory)

    val systemModelListenerCaptor = ArgumentCaptor.forClass(classOf[UpdateListener[SystemModel]])
    val versioningListenerCaptor = ArgumentCaptor.forClass(classOf[UpdateListener[ServiceVersionMappingList]])
    doNothing().when(configurationService).subscribeTo(anyString(), anyString(), any[URL], versioningListenerCaptor.capture(), any[Class[ServiceVersionMappingList]])
    doNothing().when(configurationService).subscribeTo(anyString(), systemModelListenerCaptor.capture(), any[Class[SystemModel]])

    filter = new VersioningFilter("cluster", "node", configurationService, healthCheckService, metricsService)
    filter.init(new MockFilterConfig())

    systemModelListener = systemModelListenerCaptor.getValue
    versioningListener = versioningListenerCaptor.getValue

    systemModelListener.configurationUpdated(systemModel)

    reset(configurationService)
  }

  describe("init") {
    it("should subscribe to the configuration service") {
      filter.init(new MockFilterConfig())

      verify(configurationService).subscribeTo(anyString(), anyString(), any[URL], any[UpdateListener[ServiceVersionMappingList]], any[Class[ServiceVersionMappingList]])
      verify(configurationService).subscribeTo(anyString(), any[UpdateListener[SystemModel]], any[Class[SystemModel]])
    }
  }

  describe("destroy") {
    it("should unsubscribe from the configuration service") {
      filter.destroy()

      verify(configurationService, times(2)).unsubscribeFrom(anyString(), any[UpdateListener[_]])
    }
  }

  describe("doFilter") {
    it("should throw a 503 if the filter has not yet initialized") {
      filter.doFilter(request, response, filterChain)

      response.getStatus shouldBe HttpServletResponse.SC_SERVICE_UNAVAILABLE
    }

    it("should return on request for service root") {
      versioningListener.configurationUpdated(createDefaultVersioningConfig())

      request.setRequestURI("/")
      request.addHeader(CommonHttpHeader.ACCEPT.toString, MimeType.APPLICATION_XML.toString)

      filter.doFilter(request, response, filterChain)

      filterChain.getRequest shouldBe null
      filterChain.getResponse shouldBe null
      response.getStatus shouldBe HttpServletResponse.SC_OK
    }

    it("should return on request for version root") {
      versioningListener.configurationUpdated(createDefaultVersioningConfig())

      request.setRequestURI("/v1")
      request.addHeader(CommonHttpHeader.ACCEPT.toString, MimeType.APPLICATION_XML.toString)

      filter.doFilter(request, response, filterChain)

      filterChain.getRequest shouldBe null
      filterChain.getResponse shouldBe null
      response.getStatus shouldBe HttpServletResponse.SC_OK
    }

    it("should return multiple choices") {
      versioningListener.configurationUpdated(createDefaultVersioningConfig())

      request.setRequestURI("/nothingwerecognize")
      request.addHeader(CommonHttpHeader.ACCEPT.toString, MimeType.APPLICATION_XML.toString)

      filter.doFilter(request, response, filterChain)

      filterChain.getRequest shouldBe null
      filterChain.getResponse shouldBe null
      response.getStatus shouldBe HttpServletResponse.SC_MULTIPLE_CHOICES
    }

    it("should pass request") {
      versioningListener.configurationUpdated(createDefaultVersioningConfig())

      request.setRequestURI("/v1/somethingelse")
      request.addHeader(CommonHttpHeader.ACCEPT.toString, MimeType.APPLICATION_XML.toString)

      filter.doFilter(request, response, filterChain)

      filterChain.getRequest should not be null
      filterChain.getResponse should not be null
    }

    it("should catch bad mapping to host") {
      versioningListener.configurationUpdated(createDefaultVersioningConfig())

      request.setRequestURI("/v3/somethingelse")
      request.addHeader(CommonHttpHeader.ACCEPT.toString, MimeType.APPLICATION_XML.toString)

      filter.doFilter(request, response, filterChain)

      filterChain.getRequest shouldBe null
      filterChain.getResponse shouldBe null
      response.getStatus shouldBe HttpServletResponse.SC_BAD_GATEWAY
    }

    it("should set accept from media type parameter") {
      versioningListener.configurationUpdated(createDefaultVersioningConfig())

      request.setRequestURI("/somethingthere")
      request.addHeader(CommonHttpHeader.ACCEPT.toString, "application/vnd.vendor.service-v1+xml")

      filter.doFilter(request, response, filterChain)

      filterChain.getRequest.asInstanceOf[HttpServletRequest].getHeader(CommonHttpHeader.ACCEPT.toString) shouldBe MimeType.APPLICATION_XML.toString
    }
  }

  describe("system model configurationUpdated") {
    it("should report an issue if the local node is not defined in the system model") {
      systemModelListener.configurationUpdated {
        val systemModel = new SystemModel()
        val cluster = new ReposeCluster()
        val nodeList = new NodeList()
        val node = new Node()
        val destinationList = new DestinationList()
        val endpoint = new DestinationEndpoint()
        endpoint.setId("endpoint")
        endpoint.setDefault(true)
        destinationList.getEndpoint.add(endpoint)
        node.setId("node")
        node.setHostname("localhost")
        nodeList.getNode.add(node)
        cluster.setId("randomcluster")
        cluster.setNodes(nodeList)
        cluster.setDestinations(destinationList)
        systemModel.getReposeCluster.add(cluster)
        systemModel
      }

      verify(healthCheckServiceProxy).reportIssue(anyString(), anyString(), org.mockito.Matchers.eq(Severity.BROKEN))
    }
  }

  def createDefaultVersioningConfig(): ServiceVersionMappingList = {
    new ServiceVersionMappingList()
      .withVersionMapping(
        new ServiceVersionMapping()
          .withId("/v1")
          .withPpDestId("endpoint")
          .withMediaTypes(
            new MediaTypeList()
              .withMediaType(
                new MediaType()
                  .withBase("application/xml")
                  .withType("application/vnd.vendor.service-v1+xml")
              )
          ),
        new ServiceVersionMapping()
          .withId("/v2")
          .withPpDestId("endpoint")
          .withMediaTypes(
            new MediaTypeList()
              .withMediaType(
                new MediaType()
                  .withBase("application/xml")
                  .withType("application/vnd.vendor.service-v2+xml")
              )
          ),
        new ServiceVersionMapping()
          .withId("/v3")
          .withPpDestId("badHost")
          .withMediaTypes(
            new MediaTypeList()
              .withMediaType(
                new MediaType()
                  .withBase("application/xml")
                  .withType("application/vnd.vendor.service-v3+xml")
              )
          )
      )
  }
}
