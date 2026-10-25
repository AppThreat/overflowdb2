ThisBuild / organization := "io.appthreat"
ThisBuild / version      := "3.1.1"
ThisBuild / scalaVersion := "3.10.0"

// sbt 2 applies bare settings to every subproject, so root-only settings live on the root project
lazy val root = project
  .in(file("."))
  .aggregate(core, testdomains, traversal, formats, coreTests, traversalTests)
  .settings(
    name := "overflowdb2",
    publish / skip := true
  )

lazy val core = project.in(file("core"))
lazy val testdomains = project.in(file("testdomains")).dependsOn(core)
lazy val traversal = project.in(file("traversal")).dependsOn(core)
lazy val formats = project.in(file("formats")).dependsOn(traversal, testdomains % Test)
lazy val coreTests = project.in(file("core-tests")).dependsOn(formats, testdomains)
lazy val traversalTests = project.in(file("traversal-tests")).dependsOn(formats)

ThisBuild / libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.2.20" % Test
)

ThisBuild / scalacOptions ++= Seq(
  "-release",
  "23",
  "-deprecation"
)

ThisBuild / compile / javacOptions ++= Seq(
  "-Xlint",
  "--release=23"
) ++ {
  val javaVersion = sys.props("java.specification.version").toFloat
  assert(javaVersion.toInt >= 23, s"this build requires JDK23+ - you're using $javaVersion")
  Nil
}

ThisBuild / Test / testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-v")
ThisBuild / Test / fork := true
// sbt 2 runs the suites of a forked test JVM in parallel by default; keep sbt 1's one-at-a-time
// runs, since OverflowTest and the heap-usage monitor tests watch the JVM-wide heap
ThisBuild / Test / testForkedParallel := false
// executeTests reads it through a dynamic task, which lintUnused cannot see
Global / excludeLintKeys += testForkedParallel
// sbt 2 packs test classes+resources into jars (exportJars := true); keep exploded dirs so
// tests can read their fixtures as plain files
ThisBuild / Test / exportJars := false

ThisBuild / resolvers ++= Seq(
  Resolver.mavenLocal,
  "Sonatype OSS".at("https://oss.sonatype.org/content/repositories/public")
)

ThisBuild / licenses := List("Apache-2.0" -> uri("http://www.apache.org/licenses/LICENSE-2.0"))

ThisBuild / versionScheme := Some("semver-spec")

Global / cancelable := true
Global / onChangedBuildSource := ReloadOnSourceChanges

githubOwner := "appthreat"
githubRepository := "overflowdb2"
credentials +=
  Credentials(
    "GitHub Package Registry",
    "maven.pkg.github.com",
    "appthreat",
    sys.env.getOrElse("GITHUB_TOKEN", "N/A")
  )
