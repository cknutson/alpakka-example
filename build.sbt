import com.github.os72.protocjar.Protoc

name := "alpakka-example"
version := "0.1"
scalaVersion := "2.12.8"

crossScalaVersions := Seq("2.12.8", "2.11.12")
scalaVersion := crossScalaVersions.value.head
crossVersion := CrossVersion.binary

enablePlugins(ProtobufPlugin)
version in ProtobufConfig := "2.6.1"
protobufRunProtoc in ProtobufConfig := (args => Protoc.runProtoc("-v261" +: args.toArray))
sourceDirectories in ProtobufConfig := Seq(
  file("src") / "main" / "protobuf"
)
protobufIncludePaths in ProtobufConfig := Seq(
  file("src") / "main" / "protobuf"
)

lazy val akkaVersion = "2.5.22"
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-kafka" % "1.0.1",
  "org.apache.kafka" % "kafka-clients" % "1.1.0",
  "ch.qos.logback" % "logback-classic" % "1.2.3"
)
