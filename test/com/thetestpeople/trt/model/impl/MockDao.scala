package com.thetestpeople.trt.model.impl

import java.net.URI
import org.joda.time.DateTime
import org.joda.time.Interval
import com.thetestpeople.trt.model._
import com.thetestpeople.trt.model.jenkins._
import com.github.nscala_time.time.Imports._
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.Lock
import org.apache.oro.text.GlobCompiler
import java.util.regex.Pattern

class MockDao extends Dao {

  private val lock: Lock = new ReentrantLock

  def transaction[T](p: ⇒ T): T = {
    lock.lock()
    try
      p
    finally
      lock.unlock()
  }

  private var executions: Seq[Execution] = List()
  private var tests: Seq[Test] = List()
  private var batches: Seq[Batch] = List()
  private var analyses: Seq[Analysis] = List()
  private var executionLogs: Seq[ExecutionLogRow] = List()
  private var batchLogs: Seq[BatchLogRow] = List()
  private var jenkinsJobs: Seq[JenkinsJob] = List()
  private var jenkinsBuilds: Seq[JenkinsBuild] = List()
  private var jenkinsImportSpecs: Seq[JenkinsImportSpec] = List()
  private var systemConfiguration = SystemConfiguration()
  private var jenkinsConfiguration = JenkinsConfiguration()
  private var jenkinsConfigParams: Seq[JenkinsJobParam] = List()

  def getEnrichedExecution(id: Id[Execution]): Option[EnrichedExecution] =
    for {
      execution ← executions.find(_.id == id)
      test ← tests.find(_.id == execution.testId)
      batch ← batches.find(_.id == execution.batchId)
      logOpt = executionLogs.find(_.executionId == id).map(_.log)
    } yield EnrichedExecution(execution, test.qualifiedName, batch.nameOpt, logOpt)

  def getTestAndAnalysis(id: Id[Test], configuration: Configuration): Option[TestAndAnalysis] =
    for {
      test ← tests.find(_.id == id)
      analysisOpt = analyses.find(a ⇒ a.testId == test.id && a.configuration == configuration)
    } yield TestAndAnalysis(test, analysisOpt)

  def getTestIds(): Seq[Id[Test]] = tests.map(_.id)

  def getAnalysedTests(
    configuration: Configuration,
    testStatusOpt: Option[TestStatus] = None,
    nameOpt: Option[String] = None,
    groupOpt: Option[String] = None,
    startingFrom: Int = 0,
    limitOpt: Option[Int]): Seq[TestAndAnalysis] = {
    val allResults =
      for {
        test ← tests
        if groupOpt.forall(pattern ⇒ test.groupOpt.exists(group ⇒ matchesPattern(pattern, group)))
        if nameOpt.forall(pattern ⇒ matchesPattern(pattern, test.name))
        analysisOpt = analyses.find(a ⇒ a.testId == test.id && a.configuration == configuration)
        if testStatusOpt.forall(status ⇒ analysisOpt.exists(_.status == status))
      } yield TestAndAnalysis(test, analysisOpt)
    val sortedResults = allResults.sortBy(_.test.name).sortBy(_.test.groupOpt)
    limitOpt match {
      case Some(limit) ⇒ sortedResults.drop(startingFrom).take(limit)
      case None        ⇒ sortedResults.drop(startingFrom)
    }
  }

  def getTestsById(testIds: Seq[Id[Test]]): Seq[Test] = tests.filter(test ⇒ testIds.contains(test.id))

  def getTestCountsByConfiguration(): Map[Configuration, TestCounts] =
    getConfigurations().map { c ⇒ c -> getTestCounts(c) }.toMap

  def getTestCounts(configuration: Configuration, nameOpt: Option[String] = None, groupOpt: Option[String] = None): TestCounts = {
    val tests = getAnalysedTests(configuration, nameOpt = nameOpt, groupOpt = groupOpt)
    val passed = tests.count(_.analysisOpt.exists(_.status == TestStatus.Healthy))
    val warning = tests.count(_.analysisOpt.exists(_.status == TestStatus.Warning))
    val failed = tests.count(_.analysisOpt.exists(_.status == TestStatus.Broken))
    TestCounts(passed, warning, failed)
  }

  def upsertAnalysis(analysis: Analysis) {
    analyses = analysis +: analyses.filterNot(_.testId == analysis.testId)
  }

  def getBatch(id: Id[Batch]): Option[BatchAndLog] = batches.find(_.id == id).map { batch ⇒
    val logOpt = batchLogs.find(_.batchId == id).map(_.log)
    val importSpecIdOpt = jenkinsBuilds.find(_.batchId == id).flatMap(_.importSpecIdOpt)
    BatchAndLog(batch, logOpt, importSpecIdOpt)
  }

  def getBatches(jobIdOpt: Option[Id[JenkinsJob]] = None, configurationOpt: Option[Configuration] = None): Seq[Batch] = {
    batches
      .filter(batch ⇒ jobIdOpt.forall(jobId ⇒ areAssociated(batch, jobId)))
      .filter(batch ⇒ configurationOpt.forall(configuration ⇒ batch.configurationOpt == Some(configuration)))
      .sortBy(_.executionTime)
      .reverse
  }

  private def areAssociated(batch: Batch, jobId: Id[JenkinsJob]): Boolean = {
    for {
      build ← jenkinsBuilds
      job ← jenkinsJobs
      if job.id == jobId
      if build.jobId == job.id
      if build.batchId == batch.id
    } return true
    false
  }

  def getEnrichedExecutionsInBatch(batchId: Id[Batch], passedFilterOpt: Option[Boolean]): Seq[EnrichedExecution] =
    for {
      batch ← batches
      if batch.id == batchId
      execution ← executions
      if execution.batchId == batch.id
      test ← tests.find(_.id == execution.testId)
      if passedFilterOpt.forall(expected ⇒ execution.passed == expected)
    } yield EnrichedExecution(execution, test.qualifiedName, batch.nameOpt, logOpt = None)

  def getEnrichedExecutions(ids: Seq[Id[Execution]]): Seq[EnrichedExecution] =
    for {
      batch ← batches
      execution ← executions.filter(_.batchId == batch.id)
      if execution.batchId == batch.id
      if ids.contains(execution.id)
      test ← tests.find(_.id == execution.testId)
    } yield EnrichedExecution(execution, test.qualifiedName, batch.nameOpt, logOpt = None)

  def getExecutionsForTest(id: Id[Test]): Seq[Execution] =
    executions.filter(_.testId == id).sortBy(_.executionTime).reverse

  def getEnrichedExecutionsForTest(testId: Id[Test], configurationOpt: Option[Configuration]): Seq[EnrichedExecution] = {
    val executionsForTest =
      for {
        test ← tests.filter(_.id == testId)
        execution ← executions.filter(_.testId == testId)
        batch ← batches.find(_.id == execution.batchId)
        if configurationOpt.forall(_ == execution.configuration)
      } yield EnrichedExecution(execution, test.qualifiedName, batch.nameOpt, logOpt = None)
    executionsForTest.sortBy(_.execution.executionTime).reverse
  }

  private def isDeleted(testId: Id[Test]) = tests.find(_.id == testId).exists(_.deleted)

  def iterateAllExecutions[T](f: Iterator[ExecutionLite] ⇒ T): T =
    f(executions.filterNot(e ⇒ isDeleted(e.testId)).sortBy(e ⇒ (e.configuration, e.testId, e.executionTime)).map(executionLite).iterator)

  private def executionLite(execution: Execution) =
    ExecutionLite(
      testId = execution.testId,
      executionTime = execution.executionTime,
      passed = execution.passed,
      configuration = execution.configuration)

  def getExecutionIntervalsByConfig(): Map[Configuration, Interval] =
    executions.groupBy(_.configuration).map {
      case (configuration, configExecutions) ⇒
        val executionTimes = configExecutions.map(_.executionTime)
        configuration -> new Interval(executionTimes.min, executionTimes.max)
    }

  def getEnrichedExecutions(configurationOpt: Option[Configuration], resultOpt: Option[Boolean] = None, startingFrom: Int, limit: Int): Seq[EnrichedExecution] = {
    val all =
      for {
        batch ← batches
        execution ← executions.filter(_.batchId == batch.id)
        if configurationOpt.forall(c ⇒ c == execution.configuration)
        if resultOpt.forall(c ⇒ c == execution.passed)
        test ← tests.find(_.id == execution.testId)
      } yield EnrichedExecution(execution, test.qualifiedName, batch.nameOpt, logOpt = None)
    all.sortBy(_.qualifiedName.name).sortBy(_.qualifiedName.groupOpt).reverse.sortBy(_.executionTime).reverse.drop(startingFrom).take(limit)
  }

  def countExecutions(configurationOpt: Option[Configuration], resultOpt: Option[Boolean] = None): Int =
    executions.count(e ⇒ configurationOpt.forall(_ == e.configuration) && resultOpt.forall(_  == e.passed))

  private def nextId[T <: EntityType](ids: Seq[Id[T]]): Id[T] = {
    val allIds = ids.map(_.value)
    Id(if (allIds.isEmpty) 1 else allIds.max + 1)
  }

  def newBatch(batch: Batch, logOpt: Option[String]): Id[Batch] = {
    val newId = nextId(batches.map(_.id))
    batches +:= batch.copy(id = newId)
    for (log ← logOpt)
      batchLogs +:= BatchLogRow(newId, log)
    newId
  }

  def deleteBatches(batchIds: Seq[Id[Batch]]) = {
    val (executionIds, testIds) = executions.filter(batchIds contains _.batchId).map(e ⇒ (e.id, e.testId)).toList.unzip
    jenkinsBuilds = jenkinsBuilds.filterNot(batchIds contains _.batchId)
    analyses = analyses.filterNot(testIds contains _.testId)
    executionLogs = executionLogs.filterNot(executionIds contains _.executionId)
    executions = executions.filterNot(executionIds contains _.id)
    batchLogs = batchLogs.filterNot(batchIds contains _.batchId)
    batches = batches.filterNot(batchIds contains _.id)

    val (deleteTestIds, affectedTestIds) = testIds.partition(getExecutionsForTest(_).isEmpty)
    tests = tests.filterNot(deleteTestIds contains _.id)
    DeleteBatchResult(affectedTestIds, executionIds)
  }

  private def newTest(test: Test): Id[Test] = {
    val newId = nextId(tests.map(_.id))
    tests +:= test.copy(id = newId)
    newId
  }

  def ensureTestIsRecorded(test: Test): Id[Test] = {
    tests.find(_.qualifiedName == test.qualifiedName) match {
      case Some(test) ⇒
        test.id
      case None ⇒
        newTest(test)
    }
  }

  def markTestsAsDeleted(ids: Seq[Id[Test]], deleted: Boolean = true) {
    tests =
      tests.filterNot(ids contains _.id) ++
        tests.filter(ids contains _.id).map(_.copy(deleted = deleted))
  }

  def newExecution(execution: Execution, logOpt: Option[String]): Id[Execution] = {
    val newId = nextId(executions.map(_.id))
    executions +:= execution.copy(id = newId)
    for (log ← logOpt)
      executionLogs +:= ExecutionLogRow(newId, log)
    newId
  }

  def getExecutionLog(id: Id[Execution]) = executionLogs.find(_.executionId == id).map(_.log)

  def newJenkinsBuild(jenkinsBuild: JenkinsBuild) {
    jenkinsBuilds +:= jenkinsBuild
  }

  def getJenkinsBuild(buildUrl: URI): Option[JenkinsBuild] =
    jenkinsBuilds.find(_.buildUrl == buildUrl)

  def getJenkinsBuildUrls(): Seq[URI] = jenkinsBuilds.map(_.buildUrl)

  def getJenkinsJobs(): Seq[JenkinsJob] = jenkinsJobs

  def getJenkinsBuilds(jobUrl: URI): Seq[JenkinsBuild] =
    for {
      job ← jenkinsJobs
      build ← jenkinsBuilds
      if job.id == build.jobId
      if job.url == jobUrl
    } yield build

  def newJenkinsImportSpec(spec: JenkinsImportSpec): Id[JenkinsImportSpec] = {
    val newId = nextId(jenkinsImportSpecs.map(_.id))
    jenkinsImportSpecs +:= spec.copy(id = newId)
    newId
  }

  def getJenkinsImportSpecs: Seq[JenkinsImportSpec] = jenkinsImportSpecs

  def deleteJenkinsImportSpec(id: Id[JenkinsImportSpec]): Boolean = {
    val found = jenkinsImportSpecs.exists(_.id == id)
    jenkinsImportSpecs = jenkinsImportSpecs.filterNot(_.id == id)
    found
  }

  def getJenkinsImportSpec(id: Id[JenkinsImportSpec]): Option[JenkinsImportSpec] = jenkinsImportSpecs.find(_.id == id)

  def updateJenkinsImportSpec(updatedSpec: JenkinsImportSpec): Boolean =
    jenkinsImportSpecs.find(_.id == updatedSpec.id) match {
      case Some(spec) ⇒
        jenkinsImportSpecs = updatedSpec +: jenkinsImportSpecs.filterNot(_.id == updatedSpec.id)
        true
      case None ⇒
        false
    }

  def updateJenkinsImportSpec(id: Id[JenkinsImportSpec], lastCheckedOpt: Option[DateTime]): Boolean =
    jenkinsImportSpecs.find(_.id == id) match {
      case Some(spec) ⇒
        val updatedSpec = spec.copy(lastCheckedOpt = lastCheckedOpt)
        jenkinsImportSpecs = updatedSpec +: jenkinsImportSpecs.filterNot(_.id == id)
        true
      case None ⇒
        false
    }

  def getSystemConfiguration(): SystemConfiguration = systemConfiguration

  def updateSystemConfiguration(newConfig: SystemConfiguration) { systemConfiguration = newConfig }

  def getJenkinsConfiguration(): FullJenkinsConfiguration = FullJenkinsConfiguration(jenkinsConfiguration, jenkinsConfigParams.toList)

  def updateJenkinsConfiguration(config: FullJenkinsConfiguration) {
    jenkinsConfiguration = config.config
    jenkinsConfigParams = config.params
  }

  def ensureJenkinsJob(job: JenkinsJob): Id[JenkinsJob] = {
    jenkinsJobs.find(_.url == job.url) match {
      case Some(jobAgain) ⇒
        jobAgain.id
      case None ⇒
        val newId = nextId(jenkinsJobs.map(_.id))
        jenkinsJobs +:= job.copy(id = newId)
        newId
    }
  }

  def getConfigurations(): Seq[Configuration] = executions.map(_.configuration).distinct.sorted

  def getConfigurations(testId: Id[Test]): Seq[Configuration] =
    executions.filter(_.testId == testId).map(_.configuration).distinct.sorted

  private def matchesPattern(pattern: String, text: String) =
    globToRegex(pattern).matcher(text).matches()

  private def globToRegex(pattern: String): Pattern =
    Pattern.compile(GlobCompiler.globToPerl5(pattern.toCharArray, GlobCompiler.CASE_INSENSITIVE_MASK), Pattern.CASE_INSENSITIVE)

  def getTestNames(pattern: String): Seq[String] = {
    val regexPattern = globToRegex(pattern)
    def matches(s: String) = regexPattern.matcher(s).matches()
    for (test ← tests if matches(test.name))
      yield test.name
  }

  def getGroups(pattern: String): Seq[String] = {
    val regexPattern = globToRegex(pattern)
    def matches(s: String) = regexPattern.matcher(s).matches()
    for (test ← tests; group ← test.groupOpt if matches(group))
      yield group
  }
}
