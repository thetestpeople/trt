package com.thetestpeople.trt.model

case class TestCounts(passed: Int = 0, warning: Int = 0, failed: Int = 0) {

  lazy val total = passed + warning + failed

  def countFor(testStatusOpt: Option[TestStatus]): Int = testStatusOpt match {
    case Some(TestStatus.Pass) ⇒ passed
    case Some(TestStatus.Warn) ⇒ warning
    case Some(TestStatus.Fail) ⇒ failed
    case None                  ⇒ total
  }

}