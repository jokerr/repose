package com.rackspace.identity.repose.rackspaceauthuser

import javax.servlet._

import com.rackspace.papi.commons.config.manager.UpdateListener
import com.rackspace.papi.filter.FilterConfigHelper
import com.rackspace.papi.filter.logic.impl.FilterLogicHandlerDelegate
import com.rackspace.papi.service.config.ConfigurationService
import com.rackspace.papi.service.context.ServletContextHelper
import com.typesafe.scalalogging.slf4j.LazyLogging

class RackspaceAuthUserFilter extends Filter with LazyLogging {

  private final val DEFAULT_CONFIG = "rackspace-auth-user.cfg.xml"

  private var config: String = _
  private var handlerFactory: RackspaceAuthUserHandlerFactory = _
  private var configurationService: ConfigurationService = _


  override def init(filterConfig: FilterConfig): Unit = {
    config = new FilterConfigHelper(filterConfig).getFilterConfig(DEFAULT_CONFIG)
    logger.info(s"Initializing RackspaceAuthUserFilter using config $config")

    //Ew, nasty spring hax
    val powerApiContext = ServletContextHelper.getInstance(filterConfig.getServletContext).getPowerApiContext
    configurationService = powerApiContext.configurationService()
    handlerFactory = new RackspaceAuthUserHandlerFactory()

    val xsdURL = getClass.getResource("/META-INF/config/schema/rackspace-auth-user-configuration.xsd")
    configurationService.subscribeTo(filterConfig.getFilterName,
      config,
      xsdURL,
      handlerFactory.asInstanceOf[UpdateListener[RackspaceAuthUserConfig]],
      classOf[RackspaceAuthUserConfig])

  }

  override def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {
    new FilterLogicHandlerDelegate(request, response, chain).doFilter(handlerFactory.newHandler)

  }

  override def destroy(): Unit = {
    configurationService.unsubscribeFrom(config, handlerFactory)
  }
}