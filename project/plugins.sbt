//logLevel := Level.Debug

resolvers ++= Seq("Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/")

libraryDependencies += "org.postgresql" % "postgresql" % "42.1.1"

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.3.1")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.8.2")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.5")

addSbtPlugin("org.scalikejdbc" %% "scalikejdbc-mapper-generator" % "3.0.1")
