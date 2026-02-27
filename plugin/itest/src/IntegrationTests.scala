package io.github.alexarchambault.millmissinglink

import mill.testkit.IntegrationTester
import utest._

object IntegrationTests extends TestSuite {

  val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
  val millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
  val millWorkspaceRoot = os.Path(sys.env("MILL_WORKSPACE_ROOT"))

  // Publish the plugin to a temp local ivy repo with a stable "TEST" version.
  // This makes `io.github.alexarchambault::mill-missinglink::TEST` resolvable in the test builds
  // by passing --define ivy.home=<ivyHome> to each Mill invocation.
  val ivyHome: os.Path = os.temp.dir()

  os.call(
    cmd = Seq(
      millExecutable.toString,
      "--no-build-lock",
      "--ticker", "false",
      "plugin.publishLocal",
      "--localIvyRepo", (ivyHome / "local").toString
    ),
    cwd = millWorkspaceRoot,
    env = Map("MILL_MISSINGLINK_FORCED_VERSION" -> "TEST"),
    stderr = os.Inherit
  )

  def tests: Tests = Tests {

    def buildTester(folder: String) = IntegrationTester(
      daemonMode = false,
      workspaceSourcePath = resourceFolder / folder,
      millExecutable = millExecutable
    )

    // Run a Mill task in a test build, pointing ivy.home to our local repo so
    // that `io.github.alexarchambault::mill-missinglink::TEST` resolves to our local build.
    def myEval(tester: IntegrationTester, task: String) =
      tester.eval(Seq("--define", s"ivy.home=$ivyHome", task))

    test("simple - no conflicts") {
      val tester = buildTester("simple")
      val res = myEval(tester, "foo.missinglinkCheck")
      assert(res.isSuccess)
      assert(res.err.contains("No conflicts found"))
    }

    test("ignore source packages") {
      val tester = buildTester("ignore-source-packages")
      val res = myEval(tester, "foo.missinglinkCheck")
      assert(res.isSuccess)
    }

    test("fail on conflicts false") {
      val tester = buildTester("fail-on-conflicts-false")
      val res = myEval(tester, "foo.missinglinkCheck")
      assert(res.isSuccess)
    }

    test("with conflicts - detects missing class") {
      val tester = buildTester("with-conflicts")
      val res = myEval(tester, "app.missinglinkCheck")
      assert(!res.isSuccess)
      assert(res.err.contains("conflicts found"))
    }
  }
}
