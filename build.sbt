name := """compiler-benchmark"""

version := "1.0-SNAPSHOT"

scalaVersion in ThisBuild := "2.11.8"

// Convenient access to builds from PR validation
resolvers ++= (
  if (scalaVersion.value.endsWith("-SNAPSHOT"))
    List(
      "pr-scala snapshots old" at "http://private-repo.typesafe.com/typesafe/scala-pr-validation-snapshots/",
      "pr-scala snapshots" at "https://scala-ci.typesafe.com/artifactory/scala-pr-validation-snapshots/",
      Resolver.mavenLocal,
      Resolver.sonatypeRepo("snapshots")
    )
  else
    Nil
)

val infrastructure = project.enablePlugins(JmhPlugin).settings(
  description := "Infrastrucuture to persist benchmark results annoted with context from Git",
  autoScalaLibrary := false,
  crossPaths := false,
  libraryDependencies ++= Seq(
    "org.influxdb" % "influxdb-java" % "2.5",
    "org.eclipse.jgit" % "org.eclipse.jgit" % "4.6.0.201612231935-r",
    "com.google.guava" % "guava" % "20.0",
    "org.apache.commons" % "commons-lang3" % "3.5",
    "com.typesafe" % "config" % "1.3.1",
    "org.slf4j" % "slf4j-api" % "1.7.1",
    "org.slf4j" % "log4j-over-slf4j" % "1.7.1",  // for any java classes looking for this
    "ch.qos.logback" % "logback-classic" % "1.0.3"
  )
)

val compilation = project.enablePlugins(JmhPlugin).settings(
  // We should be able to switch this project to a broad range of Scala versions for comparative
  // benchmarking. As such, this project should only depend on the high level `MainClass` compiler API.
  description := "Black box benchmark of the compiler",
  libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value
).dependsOn(infrastructure)


val micro = project.enablePlugins(JmhPlugin).settings(
  description := "Finer grained benchmarks of compiler internals",
  libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value
).dependsOn(infrastructure)

val jvm = project.enablePlugins(JmhPlugin).settings(
  description := "Pure Java benchmarks for demonstrating performance anomalies independent from the Scala language/library",
  autoScalaLibrary := false
).dependsOn(infrastructure)

val ui = project.settings(
  scalaVersion := "2.11.8",
  libraryDependencies += "com.github.tototoshi" %% "scala-csv" % "1.3.3",
  libraryDependencies += "com.h2database" % "h2" % "1.4.192"
)

val runBatch = taskKey[Unit]("Run a batch of benchmark suites")
val runBatchVersions = settingKey[Seq[String]]("Scala versions")
val runBatchBenches = settingKey[Seq[(sbt.Project, String)]]("Benchmarks")
val runBatchSources = settingKey[Seq[String]]("Sources")

runBatchVersions := List(
  "2.11.8",
  "2.12.0-M5",
  "2.12.0-RC1"
)

runBatchBenches := List(
  (compilation, "ColdScalacBenchmark"),
  (compilation, "HotScalacBenchmark")
)

runBatchSources := List(
  "scalap",
  "better-files"
)

def setVersion(s: State, proj: sbt.Project, newVersion: String): State = {
  val extracted = Project.extract(s)
  import extracted._
  if (get(scalaVersion in proj) == newVersion) s
  else {
    val append = Load.transformSettings(Load.projectScope(currentRef), currentRef.build, rootProject, (scalaVersion in proj := newVersion) :: Nil)
    val newSession = session.appendSettings(append map (a => (a, Nil)))
    s.log.info(s"Switching to Scala version $newVersion")
    BuiltinCommands.reapply(newSession, structure, s)
  }
}

commands += Command.args("runBatch", ""){ (s: State, args: Seq[String]) =>
  val targetDir = target.value
  val outFile = targetDir / "combined.csv"

  def filenameify(s: String) = s.replaceAll("""[@/:]""", "-")
  val tasks: Seq[State => State] = for {
    p <- runBatchSources.value.map(x => (filenameify(x), s"-p source=$x"))
    (sub, b) <- runBatchBenches.value
    v <- runBatchVersions.value
  } yield {
    val argLine = s" -p _scalaVersion=$v $b ${args.mkString(" ")} ${p._2} -rf csv -rff $targetDir/${p._1}-$b-$v.csv"
    println(argLine)

    (s1: State) => {
      val s2 = setVersion(s1, sub, v)
      val extracted = Project.extract(s2)
      val (s3, _) = extracted.runInputTask(run in sub in Jmh, argLine, s2)
      s3
    }
  }
  tasks.foldLeft(s)((state: State, fun: (State => State)) => {
    val newState = fun(state)
    Project.extract(newState).runInputTask(runMain in ui in Compile, " compilerbenchmark.PlotData", newState)._1
  })
}
