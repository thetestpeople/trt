package com.thetestpeople.trt.analysis

import com.thetestpeople.trt.model._
import com.thetestpeople.trt.service.Clock
import com.thetestpeople.trt.utils.HasLogger
import com.thetestpeople.trt.utils.Utils
import com.thetestpeople.trt.utils.LockUtils._
import java.util.concurrent._
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock

/**
 * @param async -- if true, process analysis asynchronously on background worker threads. If false, perform the analysis
 *   immediately when scheduled (useful for predictable testing).
 */
class AnalysisService(dao: Dao, clock: Clock, async: Boolean = true) extends HasLogger {

  private val historicalTestCountsLock: Lock = new ReentrantLock

  private var historicalTestCountsByConfig: Map[Configuration, HistoricalTestCountsTimeline] = Map()

  /**
   * Queue of tests which need their analysis updating
   */
  private val testQueue: AnalysisQueue = new AnalysisQueue()

  private def launchAnalyserThread() {
    new Thread(new Runnable() {
      def run() =
        while (true)
          handleOneQueueItem()
    }).start()
  }

  if (async)
    launchAnalyserThread()

  private def handleOneQueueItem() {
    val testId = testQueue.take()
    logger.debug("Remaining # of tests to analyse: " + testQueue.size)
    try
      analyseTest(testId)
    catch {
      case e: Exception ⇒
        logger.error(s"Problem analysing test $testId, skipping", e)
    }
  }

  def scheduleAnalysis(testIds: List[Id[Test]]) =
    if (async)
      testIds.foreach(testQueue.offer)
    else
      testIds.foreach(analyseTest)

  def analyseTest(testId: Id[Test]) = dao.transaction {
    val testAnalyser = new TestAnalyser(clock, dao.getSystemConfiguration)
    val executions = dao.getExecutionsForTest(testId)
    for {
      (configuration, executionsForConfig) ← executions.groupBy(_.configuration)
      analysis ← testAnalyser.analyse(executionsForConfig)
    } updateAnalysis(testId, configuration, analysis)
  }

  private def updateAnalysis(testId: Id[Test], configuration: Configuration, analysis: TestAnalysis) {
    dao.upsertAnalysis(Analysis(
      testId = testId,
      configuration = configuration,
      status = analysis.status,
      weather = analysis.weather,
      consecutiveFailures = analysis.consecutiveFailures,
      failingSinceOpt = analysis.failingSinceOpt,
      lastPassedExecutionIdOpt = analysis.lastPassedExecutionOpt.map(_.id),
      lastPassedTimeOpt = analysis.lastPassedExecutionOpt.map(_.executionTime),
      lastFailedExecutionIdOpt = analysis.lastFailedExecutionOpt.map(_.id),
      lastFailedTimeOpt = analysis.lastFailedExecutionOpt.map(_.executionTime),
      whenAnalysed = analysis.whenAnalysed))
    logger.debug(s"Updated analysis for test $testId")
  }

  def recomputeHistoricalTestCounts() {
    val counts = dao.transaction {
      val systemConfiguration = dao.getSystemConfiguration
      val historicalTestAnalyser = new HistoricalTestAnalyser(systemConfiguration)
      val executionIntervalsByConfig = dao.getExecutionIntervalsByConfig
      dao.iterateAllExecutions { executions ⇒
        historicalTestAnalyser.analyseAll(executions, executionIntervalsByConfig)
      }
    }
    historicalTestCountsLock.withLock {
      historicalTestCountsByConfig = counts
    }
  }

  def clearHistoricalTestCounts() {
    historicalTestCountsLock.withLock {
      historicalTestCountsByConfig = Map()
    }
  }

  def getHistoricalTestCountsByConfig: Map[Configuration, HistoricalTestCountsTimeline] =
    historicalTestCountsLock.withLock {
      historicalTestCountsByConfig
    }

}