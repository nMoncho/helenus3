Global / concurrentRestrictions += Tags.limit(Tags.Test, 2)

ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)

addCommandAlias(
  "testCoverage",
  "; clean ; coverage; test; coverageAggregate; coverageReport; coverageOff"
)

addCommandAlias(
  "styleFix",
  "; scalafmtSbt; scalafmtAll; headerCreateAll; scalafixAll"
)

addCommandAlias(
  "styleCheck",
  "; scalafmtCheckAll; headerCheckAll; scalafixAll --check"
)

Global / resolvers += "Akka library repository".at("https://repo.akka.io/maven")

lazy val root = project
    .in(file("."))
    .settings(basicSettings)
    .settings(
      publish / skip := true,
      mimaFailOnNoPrevious := false,
      Test / testOptions += Tests.Setup(() => EmbeddedDatabase.start())
    )
    .aggregate(core, pekko, akka, akkaBusl)

lazy val basicSettings = Seq(
  organization := "net.nmoncho",
  description := "Helenus is collection of Scala utilities for Apache Cassandra",
  scalaVersion := Dependencies.Version.scala,
  startYear := Some(2021),
  homepage := Some(url("https://github.com/nMoncho/helenus")),
  licenses := Seq("MIT License" -> new URL("http://opensource.org/licenses/MIT")),
  headerLicense := Some(HeaderLicense.MIT("2021", "the original author or authors")),
  sonatypeCredentialHost := "s01.oss.sonatype.org",
  sonatypeRepository := "https://s01.oss.sonatype.org/service/local",
  developers := List(
    Developer(
      "nMoncho",
      "Gustavo De Micheli",
      "gustavo.demicheli@gmail.com",
      url("https://github.com/nMoncho")
    )
  ),
  scalacOptions := (Opts.compile.encoding("UTF-8") :+
      Opts.compile.deprecation :+
      Opts.compile.unchecked :+
      "-feature" :+
      "-release" :+
      "11" :+
      "-Wunused:all" :+
      "-Xcheck-macros" :+
      "-language:higherKinds"),
  (Test / testOptions) += Tests.Argument("-oF"),
  semanticdbEnabled := true,
  semanticdbVersion := scalafixSemanticdb.revision
)

lazy val core = project
    .settings(basicSettings)
    .settings(
      name := "helenus-core",
      Test / testOptions += Tests.Setup(() => EmbeddedDatabase.start()),
      libraryDependencies ++= Seq(
        Dependencies.ossJavaDriver % Provided,
        Dependencies.slf4j,
        // Test Dependencies
        Dependencies.mockito       % Test,
        Dependencies.scalaCheck    % Test,
        Dependencies.scalaTest     % Test,
        Dependencies.scalaTestPlus % Test,
        Dependencies.logback       % Test,
        "net.java.dev.jna"         % "jna" % "5.14.0" % Test // Fixes M1 JNA issue
      )
    )

lazy val akka = project
    .settings(basicSettings)
    .dependsOn(core % "compile->compile;test->test")
    .settings(
      name := "helenus-akka",
      Test / testOptions += Tests.Setup(() => EmbeddedDatabase.start()),
      // 5.x changed to business license
      dependencyUpdatesFilter -= moduleFilter(organization = "com.lightbend.akka"),
      // 2.7.x changed to business license
      dependencyUpdatesFilter -= moduleFilter(organization = "com.typesafe.akka"),
      libraryDependencies ++= Seq(
        Dependencies.alpakka.cross(CrossVersion.for3Use2_13)     % "provided,test",
        Dependencies.akkaTestKit.cross(CrossVersion.for3Use2_13) % Test,
        // Adding this until Alpakka aligns version with Akka TestKit
        ("com.typesafe.akka" %% "akka-stream" % Dependencies.Version.akka).cross(
          CrossVersion.for3Use2_13
        ) % "provided,test"
      )
    )

lazy val akkaBusl = project
    .in(file("akka-busl"))
    .settings(basicSettings)
    .dependsOn(core % "compile->compile;test->test")
    .settings(
      name := "helenus-akka-busl",
      Test / testOptions += Tests.Setup(() => EmbeddedDatabase.start()),
      libraryDependencies ++= Seq(
        Dependencies.alpakkaBusl     % "provided,test",
        Dependencies.akkaTestKitBusl % Test,
        // Adding this until Alpakka aligns version with Akka TestKit
        "com.typesafe.akka" %% "akka-stream" % Dependencies.Version.akkaBusl % "provided,test"
      )
    )

lazy val pekko = project
    .settings(basicSettings)
    .dependsOn(core % "compile->compile;test->test")
    .settings(
      name := "helenus-pekko",
      Test / testOptions += Tests.Setup(() => EmbeddedDatabase.start()),
      libraryDependencies ++= Seq(
        Dependencies.pekkoConnector % "provided,test",
        Dependencies.pekkoTestKit   % Test,
        // Adding this until Alpakka aligns version with Pekko TestKit
        "org.apache.pekko" %% "pekko-stream" % Dependencies.Version.pekkoTestKit % "provided,test"
      )
    )
