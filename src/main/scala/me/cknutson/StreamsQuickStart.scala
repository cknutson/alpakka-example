package me.cknutson

import java.nio.file.Paths

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{FileIO, Flow, Keep, RunnableGraph, Sink, Source}
import akka.stream.{ActorMaterializer, IOResult}
import akka.util.ByteString

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object StreamsQuickStart extends App {
  final val FILE_NAME = "factorials.txt"
  val system: ActorSystem = ActorSystem("QuickStart")
  implicit val materializer: ActorMaterializer = ActorMaterializer()(system)
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  // Build a graph with re-usable components
  val graph: RunnableGraph[Future[IOResult]] =
    source
      .via(factorial)
      .via(convert)
      .toMat(sink(FILE_NAME))(Keep.right)

  // Build the same graph in a more in-line type of fashion
  val graph2: RunnableGraph[Future[IOResult]] =
    source
      .scan(BigInt(1))((acc, next) => acc * next)
      .map(s => ByteString(s"$s\n"))
      .toMat(FileIO.toPath(Paths.get(FILE_NAME)))(Keep.right)

  // Run it! Materializes the graph, waits for it to finish execution, and finally kills the program
  val done: Future[IOResult] = graph2.run()
  done.onComplete { status =>
    status match {
      case Success(ioResult) =>
        println("stream finished successfully!")
      case Failure(e) =>
        println(s"Stream failed with $e")
    }
    system.terminate()
  }

  def source: Source[Int, NotUsed] = Source(1 to 100)
  def factorial: Flow[Int, BigInt, NotUsed] = Flow[Int].scan(BigInt(1))((acc, next) => acc * next)
  def convert: Flow[BigInt, ByteString, NotUsed] = Flow[BigInt].map(s => ByteString(s + "\n"))
  def sink(filename: String): Sink[ByteString, Future[IOResult]] = FileIO.toPath(Paths.get(filename))
}
