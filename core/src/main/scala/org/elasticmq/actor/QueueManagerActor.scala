package org.elasticmq.actor

import akka.actor.{ActorRef, Props}
import org.elasticmq._
import org.elasticmq.actor.queue.{QueueActor, QueueEvent}
import org.elasticmq.actor.reply._
import org.elasticmq.msg._
import org.elasticmq.util.{Logging, NowProvider}

import scala.reflect._

class QueueManagerActor(nowProvider: NowProvider, limits: Limits, queueEventListener: Option[ActorRef])
    extends ReplyingActor
    with Logging {
  type M[X] = QueueManagerMsg[X]
  val ev: ClassTag[QueueManagerMsg[Unit]] = classTag[M[Unit]]

  private val queues = collection.mutable.HashMap[String, ActorRef]()

  def receiveAndReply[T](msg: QueueManagerMsg[T]): ReplyAction[T] =
    msg match {
      case CreateQueue(queueData) =>
        if (queues.contains(queueData.name)) {
          logger.debug(s"Cannot create queue, as it already exists: $queueData")
          Left(new QueueAlreadyExists(queueData.name))
        } else {
          logger.info(s"Creating queue $queueData")
          Limits.verifyQueueName(queueData.name, queueData.isFifo, limits) match {
            case Left(error) =>
              Left(QueueCreationError(queueData.name, error))
            case Right(_) =>
              val actor = createQueueActor(nowProvider, queueData, queueEventListener)
              queues(queueData.name) = actor
              queueEventListener.foreach(_ ! QueueEvent.QueueCreated(queueData))
              Right(actor)
          }
        }

      case DeleteQueue(queueName) =>
        logger.info(s"Deleting queue $queueName")
        queues.remove(queueName).foreach(context.stop)
        queueEventListener.foreach(_ ! QueueEvent.QueueDeleted(queueName))

      case LookupQueue(queueName) =>
        val result = queues.get(queueName)

        logger.debug(s"Looking up queue $queueName, found?: ${result.isDefined}")
        result

      case ListQueues() => queues.keySet.toSeq
    }

  protected def createQueueActor(
      nowProvider: NowProvider,
      queueData: QueueData,
      queueEventListener: Option[ActorRef]
  ): ActorRef = {
    val deadLetterQueueActor = queueData.deadLettersQueue.flatMap { qd => queues.get(qd.name) }
    val copyMessagesToQueueActor = queueData.copyMessagesTo.flatMap { queueName => queues.get(queueName) }
    val moveMessagesToQueueActor = queueData.moveMessagesTo.flatMap { queueName => queues.get(queueName) }

    context.actorOf(
      Props(
        new QueueActor(
          nowProvider,
          queueData,
          deadLetterQueueActor,
          copyMessagesToQueueActor,
          moveMessagesToQueueActor,
          queueEventListener
        )
      )
    )
  }
}
