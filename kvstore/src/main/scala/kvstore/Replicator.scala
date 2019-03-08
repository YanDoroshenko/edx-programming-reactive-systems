package kvstore

import akka.actor.{Actor, ActorLogging, ActorRef, Props}

import scala.concurrent.duration._

import scala.language.postfixOps

object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class RetryReplicate(key: String, valueOption: Option[String], seq: Long)
  case class Replicated(key: String, id: Long)

  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}

class Replicator(val replica: ActorRef) extends Actor with ActorLogging {
  import Replicator._
  import Replica._
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]
  // a sequence of not-yet-sent snapshots (you can disregard this if not implementing batching)
  var pending = Vector.empty[Snapshot]

  var _seqCounter = 0L
  def nextSeq(): Long = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }

  def receive: Receive = {
    case Replicate(key, valueOption, id) =>
      val seq = nextSeq()
      log.info("replicate key {} on {} with seq {}", key, replica, seq)
      replica ! Snapshot(key, valueOption, seq)
      acks += ((seq, (sender, Replicate(key, valueOption, id))))
      context.system.scheduler.scheduleOnce(100 milliseconds) {
        self ! RetryReplicate(key, valueOption, seq)
      }

    case RetryReplicate(key, valueOption, seq) if acks get seq nonEmpty =>
      log.debug("re-send snapshot request with seq {}", seq)
      replica ! Snapshot(key, valueOption, seq)
      context.system.scheduler.scheduleOnce(100 milliseconds) {
        self ! RetryReplicate(key, valueOption, seq)
      }

    case SnapshotAck(_, seq) if acks get seq nonEmpty =>
      for ((sender, replicate) <- acks get seq) {
        log.debug("ack for seq {}, forward it to {}", seq, sender)
        sender ! Replicated(replicate.key, replicate.id)
        acks -= seq
      }
  }
}
