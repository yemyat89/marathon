package mesosphere.marathon

import javax.inject.{ Inject, Named }

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.collect.Lists
import com.google.common.eventbus.EventBus
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.AppUpdate
import mesosphere.marathon.event._
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.AppRepository
import mesosphere.marathon.tasks._
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.mesos.{ TaskBuilder, protos }
import mesosphere.util.RateLimiters
import org.apache.log4j.Logger
import org.apache.mesos.Protos._
import org.apache.mesos.{ Scheduler, SchedulerDriver }

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.{ Failure, Success }

trait SchedulerCallbacks {
  def disconnected(): Unit
}

object MarathonScheduler {
  private class MarathonSchedulerCallbacksImpl(serviceOption: Option[MarathonSchedulerService]) extends SchedulerCallbacks {
    override def disconnected(): Unit = {
      // Abdicate leadership when we become disconnected from the Mesos master.
      serviceOption.foreach(_.abdicateLeadership())
    }
  }

  val callbacks: SchedulerCallbacks = new MarathonSchedulerCallbacksImpl(Some(Main.injector.getInstance(classOf[MarathonSchedulerService])))
}

class MarathonScheduler @Inject() (
    @Named(EventModule.busName) eventBus: Option[EventBus],
    @Named("restMapper") mapper: ObjectMapper,
    appRepository: AppRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    frameworkIdUtil: FrameworkIdUtil,
    taskIdUtil: TaskIdUtil,
    rateLimiters: RateLimiters,
    config: MarathonConf) extends Scheduler {

  private val log = Logger.getLogger(getClass.getName)

  import mesosphere.mesos.protos.Implicits._
  import mesosphere.util.ThreadPoolContext.context

  implicit val zkTimeout = config.zkFutureTimeout

  /**
    * Returns a future containing the optional most recent version
    * of the specified app from persistent storage.
    */
  protected[marathon] def currentAppVersion(
    appId: String): Future[Option[AppDefinition]] = appRepository.currentVersion(appId)

  override def registered(driver: SchedulerDriver, frameworkId: FrameworkID, master: MasterInfo) {
    log.info("Registered as %s to master '%s'".format(frameworkId.getValue, master.getId))
    frameworkIdUtil.store(frameworkId)
  }

  override def reregistered(driver: SchedulerDriver, master: MasterInfo) {
    log.info("Re-registered to %s".format(master))
  }

  override def resourceOffers(driver: SchedulerDriver, offers: java.util.List[Offer]) {
    // Check for any tasks which were started but never entered TASK_RUNNING
    // TODO resourceOffers() doesn't feel like the right place to run this
    val toKill = taskTracker.checkStagedTasks
    if (toKill.nonEmpty) {
      log.warn(s"There are ${toKill.size} tasks stuck in staging which will be killed")
      log.info(s"About to kill these tasks: $toKill")
      for (task <- toKill)
        driver.killTask(protos.TaskID(task.getId))
    }
    for (offer <- offers.asScala) {
      try {
        log.debug("Received offer %s".format(offer))

        val apps = taskQueue.removeAll()
        var i = 0
        var found = false

        while (i < apps.size && !found) {
          // TODO launch multiple tasks if the offer is big enough
          val app = apps(i)

          newTask(app, offer) match {
            case Some((task, ports)) =>
              val taskInfos = Lists.newArrayList(task)
              log.debug("Launching tasks: " + taskInfos)

              val marathonTask = MarathonTasks.makeTask(
                task.getTaskId.getValue, offer.getHostname, ports,
                offer.getAttributesList.asScala.toList, app.version)
              taskTracker.created(app.id, marathonTask)
              driver.launchTasks(Lists.newArrayList(offer.getId), taskInfos)
              found = true

            case None =>
              taskQueue.add(app)
          }

          i += 1
        }

        if (!found) {
          log.debug("Offer doesn't match request. Declining.")
          // Add it back into the queue so the we can try again
          driver.declineOffer(offer.getId)
        }
        else {
          taskQueue.addAll(apps.drop(i))
        }
      }
      catch {
        case t: Throwable =>
          log.error("Caught an exception. Declining offer.", t)
          // Ensure that we always respond
          driver.declineOffer(offer.getId)
      }
    }
  }

  override def offerRescinded(driver: SchedulerDriver, offer: OfferID) {
    log.info("Offer %s rescinded".format(offer))
  }

  override def statusUpdate(driver: SchedulerDriver, status: TaskStatus) {
    log.info("Received status update for task %s: %s (%s)"
      .format(status.getTaskId.getValue, status.getState, status.getMessage))

    val appID = taskIdUtil.appID(status.getTaskId)

    if (status.getState.eq(TaskState.TASK_FAILED)
      || status.getState.eq(TaskState.TASK_FINISHED)
      || status.getState.eq(TaskState.TASK_KILLED)
      || status.getState.eq(TaskState.TASK_LOST)) {

      // Remove from our internal list
      taskTracker.terminated(appID, status).foreach(taskOption => {
        taskOption match {
          case Some(task) => postEvent(status, task)
          case None       => log.warn(s"Couldn't post event for ${status.getTaskId}")
        }

        if (rateLimiters.tryAcquire(appID)) {
          scale(driver, appID)
        }
        else {
          log.warn(s"Rate limit reached for $appID")
        }
      })
    }
    else if (status.getState.eq(TaskState.TASK_RUNNING)) {
      taskTracker.running(appID, status).onComplete {
        case Success(task) => postEvent(status, task)
        case Failure(t) =>
          log.warn(s"Couldn't post event for ${status.getTaskId}", t)
          log.warn(s"Killing task ${status.getTaskId}")
          driver.killTask(status.getTaskId)
      }
    }
    else if (status.getState.eq(TaskState.TASK_STAGING) && !taskTracker.contains(appID)) {
      log.warn(s"Received status update for unknown app $appID")
      log.warn(s"Killing task ${status.getTaskId}")
      driver.killTask(status.getTaskId)
    }
    else {
      taskTracker.statusUpdate(appID, status).onComplete {
        case Success(t) =>
          t match {
            case None =>
              log.warn(s"Killing task ${status.getTaskId}")
              driver.killTask(status.getTaskId)
            case _ =>
          }
        case _ =>
      }
    }
  }

  override def frameworkMessage(driver: SchedulerDriver, executor: ExecutorID, slave: SlaveID, message: Array[Byte]) {
    log.info("Received framework message %s %s %s ".format(executor, slave, message))
    eventBus.foreach(_.post(MesosFrameworkMessageEvent(executor.getValue, slave.getValue, message)))
  }

  override def disconnected(driver: SchedulerDriver) {
    log.warn("Disconnected")

    // Disconnection from the Mesos master has occurred. Thus, call the scheduler callbacks.
    MarathonScheduler.callbacks.disconnected()
  }

  override def slaveLost(driver: SchedulerDriver, slave: SlaveID) {
    log.info("Lost slave %s".format(slave))
  }

  override def executorLost(driver: SchedulerDriver, executor: ExecutorID, slave: SlaveID, p4: Int) {
    log.info("Lost executor %s %s %s ".format(executor, slave, p4))
  }

  override def error(driver: SchedulerDriver, message: String) {
    log.warn("Error: %s".format(message))
    suicide()
  }

  // TODO move stuff below out of the scheduler

  def startApp(driver: SchedulerDriver, app: AppDefinition): Future[_] = {
    currentAppVersion(app.id).flatMap { appOption =>
      require(appOption.isEmpty, s"Already started app '${app.id}'")

      val persistenceResult = appRepository.store(app).map { _ =>
        log.info(s"Starting app ${app.id}")
        rateLimiters.setPermits(app.id, app.taskRateLimit)
        scale(driver, app)
      }

      persistenceResult.map { _ => healthCheckManager.reconcileWith(app) }
    }
  }

  def stopApp(driver: SchedulerDriver, app: AppDefinition): Future[_] = {
    appRepository.expunge(app.id).map { successes =>
      if (!successes.forall(_ == true)) {
        throw new StorageException("Error expunging " + app.id)
      }

      healthCheckManager.removeAllFor(app.id)

      log.info(s"Stopping app ${app.id}")
      val tasks = taskTracker.get(app.id)

      for (task <- tasks) {
        log.info(s"Killing task ${task.getId}")
        driver.killTask(protos.TaskID(task.getId))
      }
      taskQueue.purge(app)
      taskTracker.shutDown(app.id)
    }
  }

  def updateApp(
    driver: SchedulerDriver,
    id: String,
    appUpdate: AppUpdate): Future[AppDefinition] = {
    appRepository.currentVersion(id).flatMap {
      case Some(currentVersion) =>
        val updatedApp = appUpdate(currentVersion)

        healthCheckManager.reconcileWith(updatedApp)

        appRepository.store(updatedApp).map { _ =>
          update(driver, updatedApp, appUpdate)
          updatedApp
        }

      case _ => throw new UnknownAppException(id)
    }
  }

  /**
    * Make sure all apps are running the configured amount of tasks.
    *
    * Should be called some time after the framework re-registers,
    * to give Mesos enough time to deliver task updates.
    * @param driver scheduler driver
    */
  def reconcileAndScaleTasks(driver: SchedulerDriver): Unit = {
    appRepository.appIds().map(_.toSet).onComplete {
      case Success(appIds) =>
        log.info("Syncing tasks for all apps")

        for (appId <- appIds) scale(driver, appId)

        val knownTaskStatuses = appIds.flatMap { appId =>
          taskTracker.get(appId).flatMap(_.getStatusesList.asScala.lastOption)
        }

        // This seems useful only if an app is killed but the tasks are not killed
        // immediately. Therefore, we instruct the driver to kill these tasks again.
        for (unknownAppId <- taskTracker.list.keySet -- appIds) {
          log.warn(s"App $unknownAppId exists in TaskTracker, but not App store. The app was likely terminated. Will now expunge.")
          for (orphanTask <- taskTracker.get(unknownAppId)) {
            log.info(s"Killing task ${orphanTask.getId}")
            driver.killTask(protos.TaskID(orphanTask.getId))
          }
          taskTracker.shutDown(unknownAppId)
        }

        log.info("Requesting task reconciliation with the Mesos master")
        log.debug(s"Tasks to reconcile: $knownTaskStatuses")
        driver.reconcileTasks(knownTaskStatuses.asJava)

      case Failure(t) =>
        log.warn("Failed to get task names", t)
    }
  }

  private def newTask(app: AppDefinition,
                      offer: Offer): Option[(TaskInfo, Seq[Long])] = {
    // TODO this should return a MarathonTask
    new TaskBuilder(app, taskIdUtil.newTaskId, taskTracker, mapper).buildIfMatches(offer)
  }

  /**
    * Ensures current application parameters (resource requirements, URLs,
    * command, and constraints) are applied consistently across running
    * application instances.
    *
    * @param driver
    * @param updatedApp
    * @param appUpdate
    */
  private def update(driver: SchedulerDriver, updatedApp: AppDefinition, appUpdate: AppUpdate): Unit = {
    // TODO: implement app instance restart logic
  }

  /**
    * Make sure the app is running the correct number of instances
    * @param driver
    * @param app
    */
  def scale(driver: SchedulerDriver, app: AppDefinition): Unit = {
    taskTracker.get(app.id).synchronized {
      val currentCount = taskTracker.count(app.id)
      val targetCount = app.instances

      if (targetCount > currentCount) {
        log.info(s"Need to scale ${app.id} from $currentCount up to $targetCount instances")

        val queuedCount = taskQueue.count(app)
        val toQueue = targetCount - (currentCount + queuedCount)

        if (toQueue > 0) {
          log.info(s"Queueing $toQueue new tasks for ${app.id} ($queuedCount queued)")
          for (i <- 0 until toQueue)
            taskQueue.add(app)
        }
        else {
          log.info("Already queued %d tasks for %s. Not scaling.".format(queuedCount, app.id))
        }
      }
      else if (targetCount < currentCount) {
        log.info(s"Scaling ${app.id} from $currentCount down to $targetCount instances")
        taskQueue.purge(app)

        val toKill = taskTracker.take(app.id, currentCount - targetCount)
        log.info(s"Killing tasks: ${toKill.map(_.getId)}")
        for (task <- toKill) {
          driver.killTask(protos.TaskID(task.getId))
        }
      }
      else {
        log.info(s"Already running ${app.instances} instances of ${app.id}. Not scaling.")
      }
    }
  }

  private def scale(driver: SchedulerDriver, appName: String): Unit = {
    currentAppVersion(appName).onSuccess {
      case Some(app) => scale(driver, app)
      case _         => log.warn("App %s does not exist. Not scaling.".format(appName))
    }
  }

  private def suicide(): Unit = {
    log.fatal("Committing suicide")

    // Asynchronously call sys.exit() to avoid deadlock due to the JVM shutdown hooks
    Future {
      sys.exit(9)
    } onFailure {
      case t: Throwable => log.fatal("Exception while committing suicide", t)
    }
  }

  private def postEvent(status: TaskStatus, task: MarathonTask): Unit = {
    eventBus.foreach { bus =>
      log.info("Sending event notification.")
      bus.post(
        MesosStatusUpdateEvent(
          status.getSlaveId.getValue,
          status.getTaskId.getValue,
          status.getState.name,
          taskIdUtil.appID(status.getTaskId),
          task.getHost,
          task.getPortsList.asScala
        )
      )
    }
  }
}
