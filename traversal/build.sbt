name := "odb2-traversal"

libraryDependencies ++= Seq(
  "org.reflections" % "reflections" % "0.10.2"
)
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
