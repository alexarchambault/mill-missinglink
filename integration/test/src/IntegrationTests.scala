package io.github.alexarchambault.millmissinglink

import mill.testkit.IntegrationTester
import utest.*

import scala.util.Using

object IntegrationTests extends TestSuite {

  private def envPath(name: String) = sys.env
    .get(name)
    .map(os.Path(_))
    .getOrElse(sys.error(s"$name not set"))

  private lazy val resourceFolder = envPath("MILL_TEST_RESOURCE_DIR")
  private lazy val millExecutable = envPath("MILL_EXECUTABLE_PATH")

  private lazy val localRepo = envPath("MILL_MISSINGLINK_INTEGRATION_LOCAL_REPO")

  private lazy val pluginVersion = sys.env.getOrElse(
    "MILL_MISSINGLINK_INTEGRATION_VERSION",
    sys.error("MILL_MISSINGLINK_INTEGRATION_VERSION not set")
  )

  private lazy val env = Map(
    "COURSIER_REPOSITORIES" -> {
      Option(System.getenv("COURSIER_REPOSITORIES")).getOrElse("ivy2Local|central") + "|" +
        localRepo.toNIO.toUri.toASCIIString
    }
  )

  def tests: Tests = Tests {

    def buildTester(folder: String) = IntegrationTester(
      daemonMode = false,
      workspaceSourcePath = resourceFolder / folder,
      millExecutable = millExecutable
    )

    test("simple - no conflicts") {
      Using.resource(buildTester("simple")) { tester =>
        val res = tester.eval("__.missinglinkCheck", stdin = os.Inherit, stdout = os.Inherit, env = env)
        assert(res.isSuccess)
        assert(res.err.contains("No conflicts found"))
      }
    }

    test("ignore source packages") {
      Using.resource(buildTester("ignore-source-packages")) { tester =>
        val res = tester.eval("__.missinglinkCheck", stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit, env = env)
        assert(res.isSuccess)
      }
    }

    test("fail on conflicts false") {
      Using.resource(buildTester("fail-on-conflicts-false")) { tester =>
        val res = tester.eval("__.missinglinkCheck", stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit, env = env)
        assert(res.isSuccess)
      }
    }

    test("with conflicts - detects missing class") {
      Using.resource(buildTester("with-conflicts")) { tester =>
        val res = tester.eval("__.missinglinkCheck", stdin = os.Inherit, stdout = os.Inherit, env = env)
        assert(!res.isSuccess)
        assert(res.err.contains("conflicts found"))
      }
    }
  }
}
