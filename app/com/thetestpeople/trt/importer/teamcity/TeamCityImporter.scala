package com.thetestpeople.trt.importer.teamcity

import com.thetestpeople.trt.model.Batch
import com.thetestpeople.trt.model.Id
import com.thetestpeople.trt.model.jenkins.CiBuild
import com.thetestpeople.trt.model.jenkins.CiDao
import com.thetestpeople.trt.model.jenkins.CiImportSpec
import com.thetestpeople.trt.model.jenkins.CiJob
import com.thetestpeople.trt.service.BatchRecorder
import com.thetestpeople.trt.service.Clock
import com.thetestpeople.trt.utils.HasLogger
import com.thetestpeople.trt.utils.http.Http
import com.thetestpeople.trt.importer.teamcity._
import com.thetestpeople.trt.importer._

class TeamCityImporter(clock: Clock,
    http: Http,
    dao: CiDao,
    importStatusManager: CiImportStatusManager,
    batchRecorder: BatchRecorder) extends HasLogger {

  import dao.transaction

  def importBuilds(spec: CiImportSpec) {
    val alreadyImportedBuildUrls = transaction { dao.getCiBuildUrls() }.toSet
    def alreadyImported(link: TeamCityBuildLink) = alreadyImportedBuildUrls contains link.webUrl
    val buildDownloader = getBuildDownloader(spec)
    val (buildType, allBuildLinks) = buildDownloader.getBuildType()
    val buildLinks = allBuildLinks.filterNot(alreadyImported)

    for (link ← buildLinks)
      importStatusManager.buildExists(spec.id, link.webUrl, buildNameOpt = Some(link.number))
    for (link ← buildLinks)
      importBuild(link, spec, buildDownloader, buildType)

    transaction { dao.updateCiImportSpec(spec.id, Some(clock.now)) }
  }

  private def importBuild(buildLink: TeamCityBuildLink, importSpec: CiImportSpec, buildDownloader: TeamCityBuildDownloader, buildType: TeamCityBuildType) {
    val buildUrl = buildLink.webUrl
    importStatusManager.buildStarted(importSpec.id, buildUrl)
    try {
      val batchIdOpt = doImportBuild(buildLink, importSpec, buildDownloader, buildType)
      importStatusManager.buildComplete(importSpec.id, buildUrl, batchIdOpt)
    } catch {
      case e: Exception ⇒
        logger.error(s"Problem importing from $buildUrl", e)
        importStatusManager.buildErrored(importSpec.id, buildUrl, e)
    }
  }

  /**
   * @return None if build had no associated test executions.
   */
  private def doImportBuild(buildLink: TeamCityBuildLink, importSpec: CiImportSpec, buildDownloader: TeamCityBuildDownloader, buildType: TeamCityBuildType): Option[Id[Batch]] = {
    val buildUrl = buildLink.webUrl
    val build = buildDownloader.getBuild(buildLink)
    if (build.finished && build.occurrences.nonEmpty) {
      val batch = new TeamCityBatchCreator(importSpec.configurationOpt).createBatch(build)
      val batchId = batchRecorder.recordBatch(batch).id

      val jobName = s"${buildType.projectName} > ${buildType.name}"
      val ciJob = CiJob(url = buildType.webUrl, name = jobName)
      val jobId = transaction { dao.ensureCiJob(ciJob) }
      val ciBuild = CiBuild(batchId = batchId, importTime = clock.now, buildUrl = buildUrl,
        buildNumberOpt = None, buildNameOpt = Some(buildType.name), jobId = jobId, importSpecIdOpt = Some(importSpec.id))
      transaction { dao.newCiBuild(ciBuild) }
      Some(batchId)
    } else
      None
  }

  private def getBuildDownloader(spec: CiImportSpec): TeamCityBuildDownloader = {
    val jobLink = TeamCityUrlParser.parse(spec.jobUrl).right.get
    val teamCityConfiguration = transaction { dao.getTeamCityConfiguration() }
    val credentialsOpt = teamCityConfiguration.credentialsOpt
    new TeamCityBuildDownloader(http, jobLink, credentialsOpt)
  }

}