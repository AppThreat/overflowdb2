name := "overflowdb2"
ThisBuild / organization := "io.appthreat"
ThisBuild / version      := "2.2.0"
ThisBuild / scalaVersion := "3.6.4"
publish / skip := true

lazy val core = project.in(file("core"))
lazy val testdomains = project.in(file("testdomains")).dependsOn(core)
lazy val traversal = project.in(file("traversal")).dependsOn(core)
lazy val formats = project.in(file("formats")).dependsOn(traversal, testdomains % Test)
lazy val coreTests = project.in(file("core-tests")).dependsOn(formats, testdomains)
lazy val traversalTests = project.in(file("traversal-tests")).dependsOn(formats)

ThisBuild / libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)

ThisBuild / scalacOptions ++= Seq(
  "-release",
  "21",
  "-deprecation"
)

ThisBuild / compile / javacOptions ++= Seq(
  "-Xlint",
  "--release=21"
) ++ {
  val javaVersion = sys.props("java.specification.version").toFloat
  assert(javaVersion.toInt >= 21, s"this build requires JDK21+ - you're using $javaVersion")
  Nil
}

ThisBuild / Test / testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-v")
ThisBuild / Test / fork := true

ThisBuild / resolvers ++= Seq(
  Resolver.mavenLocal,
  Resolver.githubPackages("appthreat/overflowdb2"),
  "Sonatype OSS".at("https://oss.sonatype.org/content/repositories/public")
)

ThisBuild / licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))

ThisBuild / versionScheme := Some("semver-spec")

Global / cancelable := true
Global / onChangedBuildSource := ReloadOnSourceChanges

githubOwner := "appthreat"
githubRepository := "overflowdb2"
githubSuppressPublicationWarning := true
credentials +=
  Credentials(
    "GitHub Package Registry",
    "maven.pkg.github.com",
    "appthreat",
    sys.env.getOrElse("GITHUB_TOKEN", "N/A")
  )
