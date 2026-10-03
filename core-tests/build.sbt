name := "odb2-core-tests"

publish / skip := true

libraryDependencies ++= Seq(
  "com.github.sbt" % "junit-interface" % "0.13.3" % Test
)

Test / testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-v")

// HeapUsageMonitorTest reads the GC MXBeans' listener lists
Test / javaOptions += "--add-opens=java.management/sun.management=ALL-UNNAMED"
