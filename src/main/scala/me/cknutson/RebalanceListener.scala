package me.cknutson

import akka.actor.{Actor, ActorLogging}
import akka.kafka.{TopicPartitionsAssigned, TopicPartitionsRevoked}

class RebalanceListener extends Actor with ActorLogging {
  def receive: Receive = {
    case TopicPartitionsAssigned(subscription, topicPartitions) =>
      log.info("Assigned: {}", topicPartitions)

    case TopicPartitionsRevoked(subscription, topicPartitions) =>
      log.info("Revoked: {}", topicPartitions)
  }
}
