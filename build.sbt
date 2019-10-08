name := "typed-payment-processor"

version := "1.0"

scalaVersion := "2.12.6"

lazy val akkaVersion = "2.6.0-M8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor"               % akkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed"         % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-typed"   % akkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
  "org.fusesource.leveldbjni" % "leveldbjni-all"    % "1.8",
  "ch.qos.logback"     % "logback-classic"          % "1.2.3",
  "org.typelevel"     %% "squants"                  % "1.4.0",
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion  % "test",
  "org.scalatest"     %% "scalatest"                % "3.0.5"      % "test"
)

fork in Test := true 