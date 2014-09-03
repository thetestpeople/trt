package controllers

import com.thetestpeople.trt.model._
import com.thetestpeople.trt.service._
import com.thetestpeople.trt.utils.Utils
import com.thetestpeople.trt.utils.HasLogger
import com.thetestpeople.trt.model.jenkins._
import play.Logger
import play.api.mvc._
import viewModel._
import java.net.URI

object Application {

  val HideBatchChartThreshold = 1000

}

class Application(service: Service, adminService: AdminService) extends Controller with HasLogger {

  import Application._

  private implicit def globalViewContext: GlobalViewContext = GlobalViewContext(service.getConfigurations())

  def index = Action {
    Redirect(routes.Application.configurations())
  }

  def configurations() = Action { implicit request ⇒
    logger.debug(s"configurations()")

    val testsSummaries =
      service.getTestCountsByConfiguration().map {
        case (configuration, testCounts) ⇒ TestsSummaryView(configuration, testCounts)
      }.toList.sortBy(_.configuration)

    val historicalCountsByConfiguration: Map[Configuration, HistoricalTestCountsTimelineView] =
      for ((configuration, historicalTestCounts) ← service.getHistoricalTestCounts())
        yield configuration -> HistoricalTestCountsTimelineView(historicalTestCounts)

    Ok(views.html.configurations(testsSummaries, historicalCountsByConfiguration))
  }

  def execution(executionId: Id[Execution]) = Action { implicit request ⇒
    logger.debug(s"execution($executionId)")
    service.getExecution(executionId) match {
      case None ⇒
        NotFound(s"Could not find test execution with id '$executionId'")
      case Some(execution) ⇒
        val executionView = new ExecutionView(execution)
        Ok(views.html.execution(executionView))
    }
  }

  def test(testId: Id[Test], configurationOpt: Option[Configuration], pageOpt: Option[Int], pageSizeOpt: Option[Int]) = Action { implicit request ⇒
    logger.debug(s"test($testId, configuration = $configurationOpt, page = $pageOpt, pageSize = $pageSizeOpt)")
    Pagination.validate(pageOpt, pageSizeOpt) match {
      case Left(errorMessage) ⇒
        BadRequest(errorMessage)
      case Right(pagination) ⇒
        val configuration = configurationOpt.getOrElse(Configuration.Default)
        handleTest(testId, configuration, pagination) match {
          case None       ⇒ NotFound(s"Could not find test with id '$testId'")
          case Some(html) ⇒ Ok(html)
        }
    }
  }

  private def handleTest(testId: Id[Test], configuration: Configuration, pagination: Pagination)(implicit request: Request[_]) =
    service.getTestAndExecutions(testId, configuration) map {
      case TestAndExecutions(test, executions) ⇒
        val executionViews = executions.map(ExecutionView)
        val testView = new TestView(test)
        val paginationData = pagination.paginationData(executions.size)
        views.html.test(testView, executionViews, Some(configuration), service.canRerun, paginationData)
    }

  def batch(batchId: Id[Batch], passedFilterOpt: Option[Boolean], pageOpt: Option[Int], pageSizeOpt: Option[Int]) = Action { implicit request ⇒
    logger.debug(s"batch($batchId, passedFilterOpt = $passedFilterOpt, page = $pageOpt, pageSize = $pageSizeOpt)")
    Pagination.validate(pageOpt, pageSizeOpt) match {
      case Left(errorMessage) ⇒
        BadRequest(errorMessage)
      case Right(pagination) ⇒
        handleBatch(batchId, passedFilterOpt, pagination) match {
          case None       ⇒ NotFound(s"Could not find batch with id '$batchId'")
          case Some(html) ⇒ Ok(html)
        }
    }
  }

  private def handleBatch(batchId: Id[Batch], passedFilterOpt: Option[Boolean], pagination: Pagination)(implicit request: Request[_]) =
    service.getBatchAndExecutions(batchId, passedFilterOpt) map {
      case BatchAndExecutions(batch, executions, logOpt) ⇒
        val batchView = new BatchView(batch, executions, logOpt)
        val paginationData = pagination.paginationData(executions.size)
        val canRerun = service.canRerun
        views.html.batch(batchView, passedFilterOpt, canRerun, paginationData)
    }

  def batchLog(batchId: Id[Batch]) = Action { implicit request ⇒
    logger.debug(s"batchLog($batchId)")
    service.getBatchAndExecutions(batchId, None) match {
      case None ⇒
        NotFound(s"Could not find batch with id '$batchId'")
      case Some(BatchAndExecutions(batch, executions, logOpt)) ⇒
        logOpt match {
          case Some(log) ⇒
            val batchView = new BatchView(batch, List(), logOpt)
            Ok(views.html.batchLog(batchView, log))
          case None ⇒
            NotFound(s"Batch $batchId does not have an associated log recorded")
        }
    }
  }

  def batches(jobIdOpt: Option[Id[JenkinsJob]], pageOpt: Option[Int], pageSizeOpt: Option[Int]) = Action { implicit request ⇒
    logger.debug(s"batches(jobId = $jobIdOpt, page = $pageOpt, pageSize = $pageSizeOpt)")
    Pagination.validate(pageOpt, pageSizeOpt) match {
      case Left(errorMessage) ⇒ BadRequest(errorMessage)
      case Right(pagination)  ⇒ Ok(handleBatches(jobIdOpt, pagination))
    }
  }

  private def handleBatches(jobIdOpt: Option[Id[JenkinsJob]], pagination: Pagination)(implicit request: Request[_]) = {
    val batches = service.getBatches(jobIdOpt).map(new BatchView(_))
    val jobs = service.getJenkinsJobs()
    val paginationData = pagination.paginationData(batches.size)
    val hideChartInitially = batches.size >= HideBatchChartThreshold
    views.html.batches(batches, jobIdOpt, jobs, paginationData, hideChartInitially)
  }

  def executions(configurationOpt: Option[Configuration], pageOpt: Option[Int], pageSizeOpt: Option[Int]) = Action { implicit request ⇒
    logger.debug(s"executions(configuration = $configurationOpt, page = $pageOpt, pageSize = $pageSizeOpt)")
    Pagination.validate(pageOpt, pageSizeOpt) match {
      case Left(errorMessage) ⇒ BadRequest(errorMessage)
      case Right(pagination)  ⇒ Ok(handleExecutions(configurationOpt, pagination))
    }
  }

  private def handleExecutions(configurationOpt: Option[Configuration], pagination: Pagination)(implicit request: Request[_]) = {
    val ExecutionsAndTotalCount(executions, totalExecutionCount) =
      service.getExecutions(configurationOpt, startingFrom = pagination.firstItem, limit = pagination.pageSize)
    val paginationData = pagination.paginationData(totalExecutionCount)
    val executionViews = executions.map(new ExecutionView(_))
    views.html.executions(executionViews, totalExecutionCount, configurationOpt, paginationData)
  }

  private def getSelectedBatchIds(request: Request[AnyContent]): List[Id[Batch]] =
    for {
      requestMap ← request.body.asFormUrlEncoded.toList
      selectedIds ← requestMap.get("selectedBatch").toList
      idString ← selectedIds
      id ← Id.parse[Batch](idString)
    } yield id

  def deleteBatches() = Action { implicit request ⇒
    val batchIds = getSelectedBatchIds(request)
    logger.debug(s"deleteBatches($batchIds)")

    service.deleteBatches(batchIds)

    val redirectTarget = previousUrlOpt.getOrElse(routes.Application.batches())
    val successMessage = deleteBatchesSuccessMessage(batchIds)
    Redirect(redirectTarget).flashing("success" -> successMessage)
  }

  private def deleteBatchesSuccessMessage(batchIds: List[Id[Batch]]): String = {
    val batchWord = if (batchIds.size == 1) "batch" else "batches"
    s"Deleted ${batchIds.size} $batchWord"
  }

  def tests(configurationOpt: Option[Configuration], testStatusOpt: Option[TestStatus], groupOpt: Option[String], pageOpt: Option[Int], pageSizeOpt: Option[Int]) =
    Action { implicit request ⇒
      Utils.time("Application.tests()") {
        logger.debug(s"tests(configuration = $configurationOpt, status = $testStatusOpt, group = $groupOpt, page = $pageOpt, pageSize = $pageSizeOpt)")
        Pagination.validate(pageOpt, pageSizeOpt) match {
          case Left(errorMessage) ⇒
            BadRequest(errorMessage)
          case Right(pagination) ⇒
            val configuration = configurationOpt.getOrElse(Configuration.Default)
            Ok(handleTests(testStatusOpt, configuration, groupOpt, pagination))
        }
      }
    }

  private def handleTests(testStatusOpt: Option[TestStatus], configuration: Configuration, groupOpt: Option[String], pagination: Pagination)(implicit request: Request[_]) = {
    val (testCounts, tests) = service.getTests(
      configuration = configuration,
      testStatusOpt = testStatusOpt,
      groupOpt = groupOpt,
      startingFrom = pagination.firstItem,
      limit = pagination.pageSize)

    val testViews = tests.map(new TestView(_))
    val testsSummary = TestsSummaryView(configuration, testCounts)
    val paginationData = pagination.paginationData(testCounts.countFor(testStatusOpt))
    val historicalTestCountsOpt = service.getHistoricalTestCounts(configuration).map(HistoricalTestCountsTimelineView.apply)
    views.html.tests(testsSummary, testViews, configuration, testStatusOpt, groupOpt, service.canRerun, paginationData, historicalTestCountsOpt)
  }

  def admin() = Action { implicit request ⇒
    logger.debug(s"admin()")
    Ok(views.html.admin())
  }

  def deleteAll() = Action { implicit request ⇒
    logger.debug(s"deleteAll()")
    adminService.deleteAll()
    Redirect(routes.Application.admin).flashing("success" -> "All data deleted")
  }

  def updateSystemConfiguration() = Action { implicit request ⇒
    logger.debug(s"updateSystemConfiguration()")
    SystemConfigurationForm.form.bindFromRequest().fold(
      formWithErrors ⇒
        BadRequest(views.html.systemConfiguration(formWithErrors)),
      systemConfiguration ⇒ {
        service.updateSystemConfiguration(systemConfiguration)
        Redirect(routes.Application.editSystemConfiguration).flashing("success" -> "Updated configuration")
      })
  }

  def editSystemConfiguration() = Action { implicit request ⇒
    logger.debug(s"editSystemConfiguration()")
    val systemConfiguration = service.getSystemConfiguration
    val populatedForm = SystemConfigurationForm.form.fill(systemConfiguration)
    Ok(views.html.systemConfiguration(populatedForm))
  }

  private def previousUrlOpt(implicit request: Request[AnyContent]): Option[Call] =
    for {
      requestMap ← request.body.asFormUrlEncoded
      values ← requestMap.get("previousURL")
      previousUrl ← values.headOption
    } yield get(previousUrl)

  private def get(url: String) = new Call("GET", url)

}