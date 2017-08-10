import sbt._
import Keys._

object Dependencies {
  val Versions = Seq(
    crossScalaVersions := Seq("2.11.8", "2.10.5"),
    scalaVersion := crossScalaVersions.value.head
  )

  object Compile {
    val spark_core = "org.apache.spark" %% "spark-core" % "2.1.1" % "provided"
    val spark_sql = "org.apache.spark" %% "spark-sql" % "2.1.1" % "provided"
    val spark = "org.apache.spark" %% "spark-mllib" % "2.1.1" % "provided"
    val breeze_natives = "org.scalanlp" %% "breeze-natives" % "0.13.1" % "provided"
    val logging = Seq(
      "org.slf4j" % "slf4j-api" % "1.7.16" % "provided",
      "org.slf4j" % "slf4j-log4j12" % "1.7.16" % "provided"
    )
  }

  object Test {
    val scalatest = "org.scalatest" %% "scalatest" % "3.0.0" % "test"
  }

  import Compile._
  val l = libraryDependencies

  val core = l ++= Seq(spark, breeze_natives, Test.scalatest) ++ logging
}
