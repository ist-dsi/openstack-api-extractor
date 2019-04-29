name := "Openstack API Extractor"

version := "0.1"

scalaVersion := "2.12.8"

val circeVersion = "0.11.1"
libraryDependencies ++= Seq(
  "org.jsoup" % "jsoup" % "1.11.3",
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "org.http4s" %% "http4s-core" % "0.20.0-M5",
  "io.swagger.core.v3" % "swagger-models" % "2.0.7",

  "com.fasterxml.jackson.core" % "jackson-core" % "2.9.8",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.9.8",
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % "2.9.8"
)