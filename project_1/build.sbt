name := "ASD_TP1"

version := "0.1"

scalaVersion := "2.12.7"
def akkaVersion = "2.5.18"

libraryDependencies ++= Seq("com.typesafe.akka" %% "akka-actor"  % akkaVersion,
                            "com.typesafe.akka" %% "akka-remote" % akkaVersion)

libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.5"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.5" % "test"