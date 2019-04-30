package me.cknutson

import akka.Done
import akka.actor.{ActorSystem, Props}
import akka.kafka.ConsumerMessage.CommittableOffsetBatch
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.{ConsumerMessage, ConsumerSettings, Subscriptions}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import me.cknutson.proto.TestPayload
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object KafkaConsumerExample extends App {
  implicit val system: ActorSystem = ActorSystem("system-c")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  val defaultConfigs = system.settings.config.getConfig("akka.kafka.consumer")
  val config = system.settings.config.getConfig("c.kafka.consumer").withFallback(defaultConfigs)
  val consumerSettings = ConsumerSettings(config, new ByteArrayDeserializer, new ByteArrayDeserializer)
  val rebalanceListener = system.actorOf(Props(new RebalanceListener))
  val topic = config.getString("topic")
  val maxBatchSize = config.getInt("maxBatchSize")
  val logInterval: Int = config.getInt("logInterval")
  val desiredMetrics = Set("heartbeat-rate", "commit-latency-avg")

  private var processedSinceLastLog: Long = 0
  private var nextLog: Long = System.currentTimeMillis() + logInterval
  private var lastLogTime: Long = System.currentTimeMillis()

  val control: DrainingControl[Done] =
    Consumer
      .committableSource(
        consumerSettings,
        Subscriptions
          .topics(topic)
          .withRebalanceListener(rebalanceListener)
      )
      .mapAsync(10) { msg: ConsumerMessage.CommittableMessage[Array[Byte], Array[Byte]] =>
        business(msg.record)
          .map(_ => msg.committableOffset)
      }
      .batch(maxBatchSize, CommittableOffsetBatch.apply)(_.updated(_))
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

  def business(record: ConsumerRecord[Array[Byte], Array[Byte]]): Future[Done] = {
    val value = TestPayload.parseFrom(record.value)
    if (value.getMessageId % 1000 == 0)
      println(s"value: $value")
    Future.successful(Done)
  }

  def reportMetrics(): Unit = {
    control.metrics.onComplete {
      case Success(metrics) =>
        metrics.filter(metricName => desiredMetrics.contains(metricName._1.name()))
          .values
          .foreach(metric => println(s"metric: ${metric.metricName().name()}, value: ${metric.metricValue()}"))
      case Failure(e) => println(s"unable to get metrics: $e")
    }
  }

  def calculateProcessRate(batchLength: Long): Unit = {
    processedSinceLastLog += batchLength
    val now = System.currentTimeMillis()
    if (now >= nextLog) {
      val took = now - lastLogTime
      val rate = Math.round((processedSinceLastLog.toDouble / took) * 1000.0)
      println(s"Processed $processedSinceLastLog events in ${took}ms ($rate/sec)")
      reportMetrics()
      nextLog = now + logInterval
      processedSinceLastLog = 0
      lastLogTime = now
    }
  }
}
