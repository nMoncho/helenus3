import sbt.*

object Dependencies {

  object Version {
    val scala = "3.3.3" // scala-steward:off

    // 'core' dependencies
    val cassandraUnit = "4.3.1.0"
    val ossJavaDriver = "4.19.0"
    val slf4j         = "2.0.17"

    // Test Dependencies
    val mockito       = "5.20.0"
    val scalaCheck    = "1.19.0"
    val scalaTest     = "3.2.19"
    val scalaTestPlus = "3.2.18.0"
    val logback       = "1.5.19"

    val akka    = "2.6.21" // 2.7 changed to business license
    val alpakka = "4.0.0" // 5.x changed to business license

    val akkaBusl    = "2.9.4"
    val alpakkaBusl = "8.0.0"

    // Monix Dependencies
    val monix = "3.4.1"

    // Pekko Dependencies
    val pekkoConnector = "1.2.0"
    val pekkoTestKit   = "1.2.1"

    val zio               = "2.1.21"
    val zioStreamsInterop = "2.0.2"
  }

  // 'core' dependencies
  val cassandraUnit = "org.cassandraunit"    % "cassandra-unit"   % Version.cassandraUnit
  val ossJavaDriver = "org.apache.cassandra" % "java-driver-core" % Version.ossJavaDriver
  val slf4j         = "org.slf4j"            % "slf4j-api"        % Version.slf4j

  // Test Dependencies
  val mockito       = "org.mockito"        % "mockito-core"    % Version.mockito
  val scalaCheck    = "org.scalacheck"    %% "scalacheck"      % Version.scalaCheck
  val scalaTest     = "org.scalatest"     %% "scalatest"       % Version.scalaTest
  val scalaTestPlus = "org.scalatestplus" %% "scalacheck-1-17" % Version.scalaTestPlus
  val logback       = "ch.qos.logback"     % "logback-classic" % Version.logback

  // 'akka' dependencies
  val alpakka     = "com.lightbend.akka" %% "akka-stream-alpakka-cassandra" % Version.alpakka
  val akkaTestKit = "com.typesafe.akka"  %% "akka-testkit"                  % Version.akka

  // 'akka-busl' dependencies
  val alpakkaBusl = "com.lightbend.akka" %% "akka-stream-alpakka-cassandra" % Version.alpakkaBusl
  val akkaTestKitBusl = "com.typesafe.akka" %% "akka-testkit" % Version.akkaBusl

  // 'monix' dependencies
  val monix         = "io.monix" %% "monix"          % Version.monix
  val monixReactive = "io.monix" %% "monix-reactive" % Version.monix

  // 'pekko' dependencies
  val pekkoConnector = "org.apache.pekko" %% "pekko-connectors-cassandra" % Version.pekkoConnector
  val pekkoTestKit   = "org.apache.pekko" %% "pekko-testkit"              % Version.pekkoTestKit

  // 'zio' dependencies
  val zio               = "dev.zio" %% "zio"                         % Version.zio
  val zioStreams        = "dev.zio" %% "zio-streams"                 % Version.zio
  val zioStreamsInterop = "dev.zio" %% "zio-interop-reactivestreams" % Version.zioStreamsInterop
  val zioTest           = "dev.zio" %% "zio-test"                    % Version.zio
  val zioTestSbt        = "dev.zio" %% "zio-test-sbt"                % Version.zio
  val zioTestMagnolia   = "dev.zio" %% "zio-test-magnolia"           % Version.zio
}
