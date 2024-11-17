name := "odb2-formats"

libraryDependencies ++= Seq(
  "com.github.tototoshi" %% "scala-csv" % "2.0.0",
  "org.scala-lang.modules" %% "scala-xml" % "2.3.0",
  "com.github.pathikrit" %% "better-files" % "3.9.2" % Test,
  "com.github.scopt" %% "scopt" % "4.1.0",
  "io.spray" %% "spray-json" % "1.3.6"
)

Test / console / scalacOptions -= "-Xlint"
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
