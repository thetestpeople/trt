package com.thetestpeople.trt.webdriver

import java.net.URI
import org.junit.runner.RunWith
import org.openqa.selenium.WebDriver
import org.openqa.selenium.phantomjs.PhantomJSDriver
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import com.thetestpeople.trt.FakeApplicationFactory
import com.thetestpeople.trt.model.impl.DummyData
import com.thetestpeople.trt.tags.SlowTest
import com.thetestpeople.trt.webdriver.screens.AutomationContext
import com.thetestpeople.trt.webdriver.screens.Site
import play.api.test.Helpers
import play.api.test.TestServer
import org.scalatest.junit.JUnitRunner
import org.openqa.selenium.firefox.FirefoxDriver

abstract class AbstractBrowserTest extends FlatSpec with Matchers {

  private val port = 9001
  private val siteUrl = new URI("http://localhost:" + port)
  private val retainBrowser = false

  protected def automate(testBlock: Site ⇒ Any) =
    Helpers.running(TestServer(port, FakeApplicationFactory.fakeApplication)) {
      val webDriver: WebDriver = new PhantomJSDriver
      //                  val webDriver: WebDriver = new FirefoxDriver
      try {
        val automationContext = AutomationContext(webDriver)
        val site = new Site(automationContext, siteUrl)
        site.restApi.deleteAll()
        testBlock(site)
      } finally
        if (!retainBrowser)
          webDriver.quit()
    }

}