package com.thetestpeople.trt.webdriver.screens

import play.api.test.TestBrowser
import org.openqa.selenium.By
import org.openqa.selenium.WebElement
import org.openqa.selenium.WebDriver
import org.joda.time.DateTime

abstract class AbstractComponent(implicit automationContext: AutomationContext) {

  protected def log(message: String) = println(s"[${new DateTime}] $message")

  protected def webDriver = automationContext.webDriver

}
