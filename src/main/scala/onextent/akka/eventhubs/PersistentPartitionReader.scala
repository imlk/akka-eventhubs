package onextent.akka.eventhubs

import akka.actor.SupervisorStrategy.{Escalate, Restart}
import akka.actor.{ActorRef, OneForOneStrategy, Props, SupervisorStrategy}
import akka.persistence.{PersistentActor, RecoveryCompleted, SaveSnapshotSuccess, SnapshotOffer}
import akka.routing.RoundRobinPool
import akka.util.Timeout
import com.microsoft.azure.eventhubs.EventHubException
import com.typesafe.scalalogging.LazyLogging
import onextent.akka.eventhubs.Connector.Ack

import scala.concurrent.duration._

object PersistentPartitionReader extends LazyLogging {

  private def props(partitionId: Int,
                    source: ActorRef,
                    eventHubConf: EventHubConf)(implicit timeout: Timeout) =
    Props(new PersistentPartitionReader(partitionId, source, eventHubConf))
  val nameBase: String = s"PersistentPartitionReader"

  def propsWithDispatcherAndRoundRobinRouter(
      dispatcher: String,
      nrOfInstances: Int,
      partitionId: Int,
      source: ActorRef,
      eventHubConf: EventHubConf)(implicit timeout: Timeout): Props = {
    props(partitionId, source, eventHubConf)
      .withDispatcher(dispatcher)
      .withRouter(RoundRobinPool(nrOfInstances = nrOfInstances,
                                 supervisorStrategy = supervise))
  }

  def supervise: SupervisorStrategy = {
    OneForOneStrategy(maxNrOfRetries = -1, withinTimeRange = Duration.Inf) {
      case e: EventHubException =>
        logger.error(s"supervise restart due to $e")
        Restart
      case e =>
        logger.error(s"supervise escalate due to $e")
        Escalate
    }
  }

}

class PersistentPartitionReader(partitionId: Int,
                                connector: ActorRef,
                                eventHubConf: EventHubConf)
    extends AbstractPartitionReader(partitionId, connector, eventHubConf)
    with PersistentActor {
  import eventHubConf._

  override def persistenceId: String = offsetPersistenceId + "_" + partitionId

  private def takeSnapshot = () => {
    if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
      saveSnapshot(state)
    }
  }

  private var persistSeqNr = 0
  private def save = () => {
    persistSeqNr += 1
    if (persistSeqNr % persistFreq == 0) {
      persistSeqNr = 0
      persist(state) { _ =>
        takeSnapshot()
      }
    }
  }

  var outstandingAcks = 0

  override def receiveCommand: Receive = {

    case ack: Ack =>
      logger.debug(s"partition $partitionId ack for ${ack.offset}")
      if (ack.offset != "") state = ack.offset
      outstandingAcks -= 1
      save()
      // kick off a wheel when outstanding acks are low
      if (outstandingAcks <= 1) {
        read().foreach(event => {
          outstandingAcks += 1
          connector ! event
        })
      }

    case _: SaveSnapshotSuccess =>
      logger.debug(s"snapshot persisted for partition $partitionId")

    case x => logger.error(s"I don't know how to handle ${x.getClass.getName}")

  }

  override def receiveRecover: Receive = {
    // BEGIN DB RECOVERY
    case offset: String =>
      state = offset
      logger.info(s"recovery for offset $state for partition $partitionId")
    case SnapshotOffer(_, snapshot: String) =>
      state = snapshot
      logger.info(
        s"recovery snapshot offer for offset $state for partition $partitionId")
    case RecoveryCompleted =>
      // kick off a wheel at init
      logger.info(
        s"recovery complete at offset $state for partition $partitionId")
      initReceiver()
      read().foreach(event => {
        outstandingAcks += 1
        connector ! event
      })
    // END DB RECOVERY
  }
}
