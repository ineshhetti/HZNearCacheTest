import sbt.Keys._

name := "HZNearCacheTest"

version := "1.0"

lazy val root = (project in file(".")).enablePlugins(PlayJava).enablePlugins(JavaServerAppPackaging)

scalaVersion := "2.11.7"

lazy val nexus = "https://nexus.ninjavan.co/"

lazy val releases = "Releases" at nexus + "repository/maven-releases/"
lazy val snapshots = "Snapshots" at nexus + "repository/maven-snapshots/"

resolvers ++= {
Seq(snapshots, releases)

}

credentials += Credentials("Sonatype Nexus", "nexus.ninjavan.co", "ninjavan", "Eiquoo3Vsheda6SheiQu5Wio")

libraryDependencies ++= Seq(
  javaJpa.exclude("org.hibernate.javax.persistence", "hibernate-jpa-2.0-api"),
  cache,
  javaWs,
  "com.fasterxml.jackson.datatype" % "jackson-datatype-hibernate4" % "2.5.1",
  "com.googlecode.json-simple" % "json-simple" % "1.1.1" ,
  "commons-beanutils" % "commons-beanutils" % "1.9.2",
  "ch.qos.logback" % "logback-classic" % "1.0.9",
  "org.powermock" % "powermock-mockito-release-full" % "1.6.2",
  "org.mockito" % "mockito-all" % "1.10.19",
  "org.apache.kafka" % "kafka-clients" % "0.9.0.0",
  "log4j" % "log4j" % "1.2.17",
  "com.hazelcast" % "hazelcast" % "3.7.1"
)
routesGenerator := InjectedRoutesGenerator