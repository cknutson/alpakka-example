libraryDependencies += "com.github.os72" % "protoc-jar" % "3.1.0" % "compile" exclude("com.google.protobuf", "protobuf-java")

addSbtPlugin("com.github.gseitz" % "sbt-protobuf" % "0.6.3" )
