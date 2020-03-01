import Dependencies._


lazy val commonSettings = Seq(
  organization := "com.evolutiongaming",
  homepage := Some(new URL("http://github.com/evolution-gaming/akka-effect")),
  startYear := Some(2019),
  organizationName := "Evolution Gaming",
  organizationHomepage := Some(url("http://evolutiongaming.com")),
  bintrayOrganization := Some("evolutiongaming"),
  scalaVersion := crossScalaVersions.value.head,
  crossScalaVersions := Seq("2.13.1", "2.12.10"),
  scalacOptions in(Compile, doc) ++= Seq("-groups", "-implicits", "-no-link-warnings"),
  resolvers += Resolver.bintrayRepo("evolutiongaming", "maven"),
  licenses := Seq(("MIT", url("https://opensource.org/licenses/MIT"))),
  releaseCrossBuild := true,
  scalacOptsFailOnWarn := Some(false)/*TODO*/ /*,
  testOptions in Test ++= Seq(Tests.Argument(TestFrameworks.ScalaTest, "-oUDNCXEHLOPQRM"))*/)


lazy val root = (project in file(".")
  settings (name := "akka-effect")
  settings commonSettings
  settings (skip in publish := true)
  aggregate(
    actor,
    persistence,
    eventsourcing,
    `akka-effect-safe-persistence-async`))

lazy val actor = (project in file("actor")
  settings (name := "akka-effect-actor")
  settings commonSettings
  settings (libraryDependencies ++= Seq(
    Akka.actor,
    Akka.slf4j   % Test,
    Akka.testkit % Test,
    Cats.core,
    Cats.effect,
    Logback.classic % Test,
    Logback.core % Test,
    Slf4j.api % Test,
    Slf4j.`log4j-over-slf4j` % Test,
    `cats-helper`,
    `executor-tools`,
    scalatest % Test,
    compilerPlugin(`kind-projector` cross CrossVersion.full))))

lazy val persistence = (project in file("persistence")
  settings (name := "akka-effect-persistence")
  settings commonSettings
  dependsOn actor % "test->test;compile->compile"
  settings (libraryDependencies ++= Seq(
    Akka.actor,
    Akka.stream,
    Akka.persistence,
    Akka.`persistence-query`,
    Akka.slf4j   % Test,
    Akka.testkit % Test,
    Cats.core,
    Cats.effect,
    `cats-helper`,
    scalatest % Test,
    `akka-persistence-inmemory` % Test,
    compilerPlugin(`kind-projector` cross CrossVersion.full))))

lazy val eventsourcing = (project in file("eventsourcing")
  settings (name := "akka-effect-eventsourcing")
  settings commonSettings
  dependsOn persistence % "test->test;compile->compile"
  settings (libraryDependencies ++= Seq(
    compilerPlugin(`kind-projector` cross CrossVersion.full))))

lazy val `akka-effect-safe-persistence-async` = (project in file("modules/safe-persistence-async")
  settings (name := "akka-effect-safe-persistence-async")
  settings commonSettings
  dependsOn eventsourcing % "test->test;compile->compile"
  settings (libraryDependencies ++= Seq(
    SafeAkka.actor,
    SafeAkka.persistence,
    SafeAkka.`persistence-async`,
    compilerPlugin(`kind-projector` cross CrossVersion.full))))