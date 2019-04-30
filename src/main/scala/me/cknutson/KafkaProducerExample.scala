package me.cknutson

import akka.actor.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import me.cknutson.proto.TestPayload
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer

import scala.concurrent.ExecutionContextExecutor

object KafkaProducerExample extends App {
  implicit val system: ActorSystem = ActorSystem("system-a")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  val config = system.settings.config.getConfig("a")
  val producerSettings = ProducerSettings(config.getConfig("kafka.producer"), new ByteArraySerializer, new ByteArraySerializer)
  val topic = config.getString("kafka.producer.topic")

  val queue: SourceQueueWithComplete[Int] =
    Source.queue[Int](config.getInt("maxQueueSize"), OverflowStrategy.backpressure)
      .map(value => getMessage(value))
      .map(message => new ProducerRecord[Array[Byte], Array[Byte]](topic, message))
      .to(Producer.plainSink(producerSettings))
      .run()

  var i = 1
  while (true) {
    queue.offer(i)
    if (i % 5000 == 0)
      println(s"sent -> $i")
    i += 1
    Thread.sleep(1)
  }

  def getMessage(id: Int): Array[Byte] = {
    TestPayload.newBuilder
      .setMessageId(id)
      .setSendTime(System.currentTimeMillis)
      .setMessage(s"sent -> $id")
      .build
      .toByteArray
  }
}
