package io.github.alexarchambault.millmissinglink

import mill._
import mill.scalalib._
import com.spotify.missinglink.{ArtifactLoader, Conflict, ConflictChecker, Java9ModuleLoader}
import com.spotify.missinglink.Conflict.ConflictCategory
import com.spotify.missinglink.datamodel.{
  Artifact,
  ArtifactBuilder,
  ArtifactName,
  ClassTypeDescriptor,
  DeclaredClass
}
import java.io.{File, FileInputStream}
import scala.jdk.CollectionConverters._

/** A Mill plugin trait that checks for classpath linkage errors using Spotify's missinglink
  * library.
  *
  * Mix this into a [[mill.scalalib.JavaModule]] to add classpath linkage checking:
  * {{{
  * import io.github.alexarchambault.millmissinglink._
  *
  * object myProject extends ScalaModule with MissingLink {
  *   def scalaVersion = "3.3.5"
  * }
  * }}}
  *
  * Then run:
  * {{{
  * mill myProject.missinglinkCheck
  * }}}
  */
trait MissingLink extends JavaModule {

  /** Fail the build if any conflicts are found. Default: `true`. */
  def missinglinkFailOnConflicts: T[Boolean] = Task { true }

  /** Packages in the calling code to ignore when reporting conflicts. A conflict where the
    * calling class is in one of these packages (or a sub-package) is suppressed. Cannot be
    * combined with [[missinglinkTargetSourcePackages]].
    */
  def missinglinkIgnoreSourcePackages: T[Seq[String]] = Task { Seq.empty[String] }

  /** If non-empty, only report conflicts where the calling class is in one of these packages (or
    * a sub-package). Cannot be combined with [[missinglinkIgnoreSourcePackages]].
    */
  def missinglinkTargetSourcePackages: T[Seq[String]] = Task { Seq.empty[String] }

  /** Packages in the called code to ignore when reporting conflicts. A conflict where the called
    * class is in one of these packages (or a sub-package) is suppressed. Cannot be combined with
    * [[missinglinkTargetDestinationPackages]].
    */
  def missinglinkIgnoreDestinationPackages: T[Seq[String]] = Task { Seq.empty[String] }

  /** If non-empty, only report conflicts where the called class is in one of these packages (or a
    * sub-package). Cannot be combined with [[missinglinkIgnoreDestinationPackages]].
    */
  def missinglinkTargetDestinationPackages: T[Seq[String]] = Task { Seq.empty[String] }

  /** Check for classpath linkage errors on this module's runtime classpath.
    *
    * Compiles the module, then uses Spotify's missinglink library to detect any missing classes
    * or method-signature mismatches that would cause [[LinkageError]]s at runtime.
    */
  def missinglinkCheck(): Command[Unit] = Task.Command {
    val log = Task.log

    val classesDir = compile().classes.path
    val classpath = runClasspath().map(_.path)
    val failOnConflicts = missinglinkFailOnConflicts()
    val ignoreSourcePkgs = missinglinkIgnoreSourcePackages()
    val targetSourcePkgs = missinglinkTargetSourcePackages()
    val ignoreDestPkgs = missinglinkIgnoreDestinationPackages()
    val targetDestPkgs = missinglinkTargetDestinationPackages()

    require(
      ignoreSourcePkgs.isEmpty || targetSourcePkgs.isEmpty,
      "missinglinkIgnoreSourcePackages and missinglinkTargetSourcePackages cannot both be set"
    )
    require(
      ignoreDestPkgs.isEmpty || targetDestPkgs.isEmpty,
      "missinglinkIgnoreDestinationPackages and missinglinkTargetDestinationPackages cannot both be set"
    )

    val artifactLoader = new ArtifactLoader()

    // Load runtime dependency artifacts (JARs and class dirs), excluding our own compiled classes
    val runtimeArtifacts: Seq[Artifact] = classpath
      .filter { p =>
        p != classesDir && os.exists(p) && (p.ext == "jar" || os.isDir(p))
      }
      .map(p => artifactLoader.load(p.toIO))

    // Load JDK bootstrap artifacts (java.lang.*, etc.)
    val bootstrapArtifacts: Seq[Artifact] = loadBootstrapArtifacts(artifactLoader, log)

    val allArtifacts: Seq[Artifact] = runtimeArtifacts ++ bootstrapArtifacts

    // Build the project artifact by reading .class files from the compiled output directory
    val projectArtifact: Artifact = buildProjectArtifact(classesDir)

    if (projectArtifact.classes().isEmpty) {
      log.info("No classes found in project build directory — did you run compile first?")
    }

    log.debug(s"Checking for conflicts in: ${projectArtifact.name().name()}")
    log.debug(s"Runtime artifacts on classpath: ${runtimeArtifacts.size}")

    val conflictChecker = new ConflictChecker()
    val rawConflicts: Seq[Conflict] = conflictChecker
      .check(projectArtifact, runtimeArtifacts.asJava, allArtifacts.asJava)
      .asScala
      .toSeq

    val filteredConflicts = applyFilters(
      rawConflicts,
      ignoreSourcePkgs,
      targetSourcePkgs,
      ignoreDestPkgs,
      targetDestPkgs
    )

    if (filteredConflicts.nonEmpty) {
      val totalBefore = rawConflicts.size
      val totalAfter = filteredConflicts.size

      if (totalBefore != totalAfter)
        log.info(
          s"$totalAfter conflicts found! ($totalBefore total before applying package filters)"
        )
      else
        log.info(s"$totalAfter conflicts found!")

      outputConflicts(filteredConflicts, log)

      if (failOnConflicts)
        throw new Exception(s"There were $totalAfter missinglink conflicts")
    } else {
      log.info("No conflicts found")
    }
  }

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  private def loadBootstrapArtifacts(
      loader: ArtifactLoader,
      log: mill.api.Logger
  ): Seq[Artifact] = {
    // On Java 8 the boot class path is exposed via a system property; on Java 9+
    // it is null and we must use the module system instead.
    val bootClasspath = System.getProperty("sun.boot.class.path")
    if (bootClasspath == null) {
      try {
        Java9ModuleLoader
          .getJava9ModuleArtifacts { (msg: String, ex: Exception) =>
            if (ex != null) log.debug(s"Warning loading Java module artifact: $msg ($ex)")
            else log.debug(s"Warning loading Java module artifact: $msg")
          }
          .asScala
          .toSeq
      } catch {
        case ex: Exception =>
          log.debug(s"Could not load Java 9 module artifacts, bootstrap classpath will be empty: $ex")
          Seq.empty
      }
    } else {
      bootClasspath
        .split(File.pathSeparator)
        .map(new File(_))
        .filter(f => f.exists() && (f.isFile || f.isDirectory))
        .map(loader.load)
        .toSeq
    }
  }

  private def buildProjectArtifact(classesDir: os.Path): Artifact = {
    val classes: Map[ClassTypeDescriptor, DeclaredClass] =
      if (os.exists(classesDir) && os.isDir(classesDir)) {
        os.walk(classesDir)
          .filter(_.ext == "class")
          .map { classFile =>
            val is = new FileInputStream(classFile.toIO)
            try com.spotify.missinglink.ClassLoader.load(is)
            finally is.close()
          }
          .map(c => c.className() -> c)
          .toMap
      } else {
        Map.empty
      }

    new ArtifactBuilder()
      .name(new ArtifactName("project"))
      .classes(classes.asJava)
      .build()
  }

  private def packageOf(descriptor: ClassTypeDescriptor): String = {
    val className = descriptor.getClassName.replace('/', '.')
    val lastDot = className.lastIndexOf('.')
    if (lastDot >= 0) className.substring(0, lastDot) else ""
  }

  private def matchesPackage(filter: String, packageName: String): Boolean =
    packageName == filter || packageName.startsWith(filter + ".")

  private def applyFilters(
      conflicts: Seq[Conflict],
      ignoreSourcePkgs: Seq[String],
      targetSourcePkgs: Seq[String],
      ignoreDestPkgs: Seq[String],
      targetDestPkgs: Seq[String]
  ): Seq[Conflict] =
    conflicts.filter { conflict =>
      val dep = conflict.dependency()
      val sourcePkg = packageOf(dep.fromClass())
      val destPkg = packageOf(dep.targetClass())

      val sourceOk =
        if (ignoreSourcePkgs.nonEmpty)
          !ignoreSourcePkgs.exists(matchesPackage(_, sourcePkg))
        else if (targetSourcePkgs.nonEmpty)
          targetSourcePkgs.exists(matchesPackage(_, sourcePkg))
        else
          true

      val destOk =
        if (ignoreDestPkgs.nonEmpty)
          !ignoreDestPkgs.exists(matchesPackage(_, destPkg))
        else if (targetDestPkgs.nonEmpty)
          targetDestPkgs.exists(matchesPackage(_, destPkg))
        else
          true

      sourceOk && destOk
    }

  private def outputConflicts(conflicts: Seq[Conflict], log: mill.api.Logger): Unit = {
    val descriptions = Map(
      ConflictCategory.CLASS_NOT_FOUND -> "Class being called not found",
      ConflictCategory.METHOD_SIGNATURE_NOT_FOUND -> "Method being called not found"
    )

    val byCategory = conflicts.groupBy(_.category())

    for ((category, conflictsInCategory) <- byCategory) {
      val desc = descriptions.getOrElse(category, category.name().replace('_', ' '))
      log.error("")
      log.error(s"Category: $desc")

      val byArtifact = conflictsInCategory.groupBy(_.usedBy())

      for ((artifactName, conflictsInArtifact) <- byArtifact) {
        log.error(s"  In artifact: ${artifactName.name()}")

        val byClass = conflictsInArtifact.groupBy(_.dependency().fromClass())

        for ((classDesc, conflictsInClass) <- byClass) {
          log.error(s"    In class: $classDesc")

          for (conflict <- conflictsInClass) {
            val dep = conflict.dependency()
            val lineNum =
              if (dep.fromLineNumber() != 0) s":${dep.fromLineNumber()}" else ""
            log.error(
              s"      In method:  ${dep.fromMethod().prettyWithoutReturnType()}$lineNum"
            )
            log.error(s"      ${dep.describe()}")
            log.error(s"      Problem: ${conflict.reason()}")
            if (conflict.existsIn() != ConflictChecker.UNKNOWN_ARTIFACT_NAME)
              log.error(s"      Found in: ${conflict.existsIn().name()}")
            log.error("      --------")
          }
        }
      }
    }
  }
}
