organization := "tv.cntt"

name := "xitrum"

version := "1.10.1-SNAPSHOT"

scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked"
)

// Put config directory in classpath for easier development (sbt console etc.)
unmanagedBase in Runtime <<= baseDirectory { base => base / "config" }

// Most Scala projects are published to Sonatype, but Sonatype is not default
// and it takes several hours to sync from Sonatype to Maven Central
resolvers += "SonatypeReleases" at "http://oss.sonatype.org/content/repositories/releases/"

// Akka ------------------------------------------------------------------------

resolvers += "Typesafe" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies += "com.typesafe.akka" % "akka-actor" % "2.0.4"

// Hazelcast -------------------------------------------------------------------

// For distributed cache and Comet
// Infinispan is good but much heavier
libraryDependencies += "com.hazelcast" % "hazelcast" % "2.4"

// http://www.hazelcast.com/documentation.jsp#Clients
// Hazelcast can be configured in Xitrum as super client or native client
libraryDependencies += "com.hazelcast" % "hazelcast-client" % "2.4"

// Jerkson ---------------------------------------------------------------------

// https://github.com/codahale/jerkson
// lift-json does not generate correctly for:
//   List(Map("user" -> List("langtu"), "body" -> List("hello world")))
resolvers += "repo.codahale.com" at "http://repo.codahale.com"

libraryDependencies += "com.codahale" % "jerkson_2.9.1" % "0.5.0"

// Scalate ---------------------------------------------------------------------

libraryDependencies += "org.fusesource.scalate" % "scalate-core" % "1.5.3"

// For Markdown
libraryDependencies += "org.fusesource.scalamd" % "scalamd" % "1.5"

// For Scalate to compile CoffeeScript to JavaScript
libraryDependencies += "org.mozilla" % "rhino" % "1.7R4"

// Other dependencies ----------------------------------------------------------

libraryDependencies += "io.netty" % "netty" % "3.5.11.Final"

libraryDependencies += "org.javassist" % "javassist" % "3.17.1-GA"

// Projects using Xitrum must provide a concrete implentation of SLF4J (Logback etc.)
libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.2" % "provided"

libraryDependencies += "org.jboss.marshalling" % "jboss-marshalling" % "1.3.15.GA"

libraryDependencies += "org.jboss.marshalling" % "jboss-marshalling-river" % "1.3.15.GA"

// For jsEscape
libraryDependencies += "org.apache.commons" % "commons-lang3" % "3.1"

libraryDependencies += "tv.cntt" %% "scaposer" % "1.2"

libraryDependencies += "tv.cntt" %% "sclasner" % "1.2"

// xitrum.imperatively uses Scala continuation, a compiler plugin --------------

autoCompilerPlugins := true

addCompilerPlugin("org.scala-lang.plugins" % "continuations" % "2.9.2")

scalacOptions += "-P:continuations:enable"

// https://github.com/harrah/xsbt/wiki/Cross-Build
//crossScalaVersions := Seq("2.9.1", "2.9.2")
scalaVersion := "2.9.2"

// Copy dev/build.sbt.end here when publishing to Sonatype
