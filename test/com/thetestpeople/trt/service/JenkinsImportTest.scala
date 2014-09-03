package com.thetestpeople.trt.service

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest._
import com.github.nscala_time.time.Imports._
import com.thetestpeople.trt.analysis.AnalysisService
import com.thetestpeople.trt.utils._
import com.thetestpeople.trt.mother.{ TestDataFactory ⇒ F }
import com.thetestpeople.trt.model.impl.DummyData
import com.thetestpeople.trt.model.impl.MockDao
import java.net.URI
import com.thetestpeople.trt.utils.http.Http
import com.thetestpeople.trt.utils.http.AlwaysFailingHttp
import com.thetestpeople.trt.utils.http.ClasspathCachingHttp

@RunWith(classOf[JUnitRunner])
class JenkinsImportTest extends FlatSpec with ShouldMatchers {

  "Importing from jenkins" should "work" in {

    val clock = FakeClock()
    val http = new ClasspathCachingHttp("")
    val Setup(service, _) = setup(http, clock)

    val specId = service.newJenkinsImportSpec(F.jenkinsImportSpec(
      jobUrl = new URI("http://ci.pentaho.com/job/pentaho-big-data-plugin/"),
      pollingInterval = 2.minutes,
      importConsoleLog = false))

    http.prefix = "webcache-pentaho-846-848"

    service.syncAllJenkins()

    service.getBatches().flatMap(_.nameOpt) should equal(List(
      "pentaho-big-data-plugin #848",
      "pentaho-big-data-plugin #847",
      "pentaho-big-data-plugin #846",
      "pentaho-big-data-plugin #57"))

    http.prefix = "webcache-pentaho-847-849"
    clock += 10.minutes

    service.syncAllJenkins()

    service.getBatches().flatMap(_.nameOpt) should equal(List(
      "pentaho-big-data-plugin #849",
      "pentaho-big-data-plugin #848",
      "pentaho-big-data-plugin #847",
      "pentaho-big-data-plugin #846",
      "pentaho-big-data-plugin #57"))

  }

  private def setup(http: Http = AlwaysFailingHttp, clock: Clock = FakeClock()) = {
    val dao = new MockDao
    val analysisService = new AnalysisService(dao, clock, async = false)
    val batchRecorder = new BatchRecorder(dao, clock, analysisService)
    val service = new ServiceImpl(dao, clock, http, analysisService)
    Setup(service, batchRecorder)
  }

  case class Setup(service: Service, batchRecorder: BatchRecorder)

}