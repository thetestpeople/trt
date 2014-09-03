package com.thetestpeople.trt.mother

import com.thetestpeople.trt.model._
import com.thetestpeople.trt.model.jenkins._
import com.thetestpeople.trt.model.impl._
import org.joda.time._
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

object TestDataFactory {

  private val testNameCounter = new AtomicInteger(1)

  def test(qualifiedName: QualifiedName): Test = test(qualifiedName.name, qualifiedName.groupOpt)

  def test(
    name: String = "test" + testNameCounter.getAndIncrement(),
    groupOpt: Option[String] = Some(DummyData.Group)): Test =
    Test(name = name, groupOpt = groupOpt)

  def batch(
    urlOpt: Option[URI] = Some(DummyData.BuildUrl),
    executionTime: DateTime = DummyData.ExecutionTime,
    durationOpt: Option[Duration] = Some(DummyData.Duration),
    nameOpt: Option[String] = Some(DummyData.BatchName),
    passed: Boolean = true,
    totalCount: Int = 10,
    passCount: Int = 5,
    failCount: Int = 5): Batch =
    Batch(
      urlOpt = urlOpt,
      executionTime = executionTime,
      durationOpt = durationOpt,
      nameOpt = nameOpt,
      passed = passed,
      totalCount = totalCount,
      passCount = passCount,
      failCount = failCount)

  def execution(
    batchId: Id[Batch],
    testId: Id[Test],
    executionTime: DateTime = DummyData.ExecutionTime,
    durationOpt: Option[Duration] = Some(DummyData.Duration),
    summaryOpt: Option[String] = Some(DummyData.Summary),
    passed: Boolean = true,
    configuration: Configuration = Configuration.Default): Execution =
    Execution(
      batchId = batchId,
      testId = testId,
      executionTime = executionTime,
      durationOpt = durationOpt,
      summaryOpt = summaryOpt,
      passed = passed,
      configuration = configuration)

  def analysis(
    testId: Id[Test],
    status: TestStatus = TestStatus.Pass,
    configuration: Configuration = Configuration.Default,
    weather: Double = DummyData.Weather,
    consecutiveFailures: Int = DummyData.ConsecutiveFailures,
    failingSinceOpt: Option[DateTime] = None,
    lastPassedExecutionIdOpt: Option[Id[Execution]] = None,
    lastPassedTimeOpt: Option[DateTime] = None,
    lastFailedExecutionIdOpt: Option[Id[Execution]] = None,
    lastFailedTimeOpt: Option[DateTime] = None,
    whenAnalysed: DateTime = DummyData.WhenAnalysed): Analysis =
    Analysis(
      testId = testId,
      configuration = configuration,
      status = status,
      weather = weather,
      consecutiveFailures = consecutiveFailures,
      failingSinceOpt = failingSinceOpt,
      lastPassedExecutionIdOpt = lastPassedExecutionIdOpt,
      lastPassedTimeOpt = lastPassedTimeOpt,
      lastFailedExecutionIdOpt = lastFailedExecutionIdOpt,
      lastFailedTimeOpt = lastFailedTimeOpt,
      whenAnalysed = whenAnalysed)

  def jenkinsImportSpec(
    jobUrl: URI = DummyData.JobUrl,
    pollingInterval: Duration = DummyData.PollingInterval,
    importConsoleLog: Boolean = true,
    lastCheckedOpt: Option[DateTime] = None,
    configurationOpt: Option[Configuration] = None): JenkinsImportSpec =
    JenkinsImportSpec(
      jobUrl = jobUrl,
      pollingInterval = pollingInterval,
      importConsoleLog = importConsoleLog,
      lastCheckedOpt = lastCheckedOpt,
      configurationOpt = configurationOpt)

  def jenkinsJob(
    url: URI = DummyData.JobUrl,
    name: String = DummyData.JobName) =
    JenkinsJob(
      url = url,
      name = name)

  def jenkinsBuild(
    batchId: Id[Batch],
    jobId: Id[JenkinsJob],
    importTime: DateTime = DummyData.ImportTime,
    buildUrl: URI = DummyData.BuildUrl) =
    JenkinsBuild(batchId, importTime, buildUrl, jobId)

}