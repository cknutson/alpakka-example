package me.cknutson

import akka.actor.{ActorSystem, Props}
import akka.kafka.ConsumerMessage.{CommittableOffset, CommittableOffsetBatch}
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, ProducerMessage, ProducerSettings, Subscriptions}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, ByteArraySerializer}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}

object KafkaPassThroughExample extends App {
  implicit val system: ActorSystem = ActorSystem("system-b")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  val defaultConsumerConfigs = system.settings.config.getConfig("akka.kafka.consumer")
  val config = system.settings.config.getConfig("b")
  val consumerConfig = config.getConfig("kafka.consumer").withFallback(defaultConsumerConfigs)
  val consumerSettings = ConsumerSettings(consumerConfig, new ByteArrayDeserializer, new ByteArrayDeserializer)
  val sourceTopic = consumerConfig.getString("topic")

  val defaultProducerConfigs = system.settings.config.getConfig("akka.kafka.producer")
  val producerConfig = config.getConfig("kafka.producer").withFallback(defaultProducerConfigs)
  val producerSettings = ProducerSettings(producerConfig, new ByteArraySerializer, new ByteArraySerializer)
  val rebalanceListener = system.actorOf(Props(new RebalanceListener))
  val sinkTopic = producerConfig.getString("topic")
  val maxBatchSize = config.getInt("maxBatchSize")

  private val logInterval: Int = config.getInt("logInterval")
  private var processedSinceLastLog: Long = 0
  private var nextLog: Long = System.currentTimeMillis() + logInterval
  private var lastLogTime: Long = System.currentTimeMillis()

  val control =
    Consumer
      .committableSource(
        consumerSettings,
        Subscriptions
          .topics(sourceTopic)
          .withRebalanceListener(rebalanceListener)
      )
      .map { msg =>
        ProducerMessage.Message[Array[Byte], Array[Byte], CommittableOffset](
          new ProducerRecord(sinkTopic, msg.record.value),
          passThrough = msg.committableOffset
        )
      }
      .via(Producer.flexiFlow(producerSettings))
      .map(_.passThrough)
      .batch(max = 500, CommittableOffsetBatch(_))(_.updated(_))
      .mapAsync(3)((batch: CommittableOffsetBatch) => {
        calculateProcessRate(batch.batchSize)
        batch.commitScaladsl
      })
      .toMat(Sink.ignore)(Keep.both)
      .mapMaterializedValue(DrainingControl.apply)
      .run()

  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run(): Unit = {
      println("Got shutdown hook, draining stream...")
      Await.result(control.drainAndShutdown, atMost = 60 seconds)
      println("Stream shut down. Good bye.")
    }
  })

  private def calculateProcessRate(batchLength: Long): Unit = {
    processedSinceLastLog += batchLength
    val now = System.currentTimeMillis()
    if (now >= nextLog) {
      val took = now - lastLogTime
      val rate = Math.round((processedSinceLastLog.toDouble / took) * 1000.0)
      println(s"Processed $processedSinceLastLog events in ${took}ms ($rate/sec)")
      nextLog = now + logInterval
      processedSinceLastLog = 0
      lastLogTime = now
    }
  }
}
