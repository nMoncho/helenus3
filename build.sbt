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

lazy val root = project
    .in(file("."))
    .settings(basicSettings)
    .settings(
      publish / skip := true,
      mimaFailOnNoPrevious := false,
      Test / testOptions += Tests.Setup(() => EmbeddedDatabase.start())
    )
    .aggregate(core)

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
      "-Ywarn-unused" :+
      "-Xcheck-macros" :+
      "-language:higherKinds" :+
      "-Xlog-implicits"),
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
        Dependencies.shapeless,
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
