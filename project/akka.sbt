val akkaKey = Option(System.getenv("AKKA_IO_REPOSITORY_KEY")).getOrElse {
    throw new IllegalStateException("Akka.io repository key is required to build Akka BUSL projects")
}

ThisBuild / resolvers += "akka-secure-mvn" at s"https://repo.akka.io/${akkaKey}/secure"
ThisBuild / resolvers += Resolver.url(
  "akka-secure-ivy",
  url(s"https://repo.akka.io/${akkaKey}/secure")
)(Resolver.ivyStylePatterns)
