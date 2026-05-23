package marklit.results

import marklit.model.*
import marklit.processor.*
import zio.*
import zio.json.*
import zio.test.*

object ResultsFileSpec extends ZIOSpecDefault:

  val testLocation = Location("test.md", 10, 1)

  def makeBlock(code: String, scalaVersion: Option[String] = None): CodeBlock =
    CodeBlock(
      code = code,
      modifiers = Set.empty,
      scopeConfig = ScopeConfig(scalaVersion = scalaVersion),
      location = testLocation
    )

  def makeBlockResult(
      code: String,
      success: Boolean = true,
      output: Option[String] = None,
      skipped: Boolean = false,
      scalaVersion: Option[String] = None
  ): BlockResult =
    BlockResult(
      block = makeBlock(code, scalaVersion),
      compileResult = Some(CompileResult(success = success, diagnostics = Nil)),
      executionOutput = output,
      error = None,
      skipped = skipped
    )

  def spec = suite("ResultsFile")(
    suite("BlockResultEntry")(
      test("creates from BlockResult") {
        val br = makeBlockResult("val x = 1", output = Some("result"))
        val entry = BlockResultEntry.fromBlockResult(br, "3.3.3")

        assertTrue(
          entry.locationKey == "test.md:10:1",
          entry.scalaVersion == "3.3.3",
          entry.code == "val x = 1",
          entry.success,
          !entry.skipped,
          entry.executionOutput == Some("result")
        )
      },

      test("marks skipped blocks") {
        val br = makeBlockResult("val x = 1", skipped = true)
        val entry = BlockResultEntry.fromBlockResult(br, "3.3.3")

        assertTrue(entry.skipped)
      }
    ),

    suite("RunResults")(
      test("creates from DocumentResult") {
        val docResult = DocumentResult(
          blockResults = Vector(
            makeBlockResult("val a = 1", output = Some("1")),
            makeBlockResult("val b = 2", output = Some("2"))
          ),
          processingTime = java.time.Duration.ofMillis(100)
        )

        val runResults =
          RunResults.fromDocumentResult(docResult, "3.3.3", "test.md")

        assertTrue(
          runResults.scalaVersion == "3.3.3",
          runResults.sourceFile == "test.md",
          runResults.blocks.size == 2
        )
      },

      test("filters skipped blocks in processedBlocks") {
        val docResult = DocumentResult(
          blockResults = Vector(
            makeBlockResult("val a = 1", output = Some("1")),
            makeBlockResult("val b = 2", skipped = true)
          ),
          processingTime = java.time.Duration.ofMillis(100)
        )

        val runResults =
          RunResults.fromDocumentResult(docResult, "3.3.3", "test.md")

        assertTrue(
          runResults.blocks.size == 2,
          runResults.processedBlocks.size == 1
        )
      },

      test("serializes to and from JSON") {
        val docResult = DocumentResult(
          blockResults =
            Vector(makeBlockResult("val x = 1", output = Some("1"))),
          processingTime = java.time.Duration.ofMillis(100)
        )

        val runResults =
          RunResults.fromDocumentResult(docResult, "3.3.3", "test.md")
        val json = runResults.toJson
        val parsed = json.fromJson[RunResults]

        assertTrue(
          parsed.isRight,
          parsed.toOption.get.scalaVersion == "3.3.3",
          parsed.toOption.get.blocks.size == 1
        )
      }
    ),

    suite("MergedResults")(
      test("merges results from multiple Scala versions") {
        val run1 = RunResults(
          scalaVersion = "2.13.12",
          sourceFile = "test.md",
          timestamp = 1000L,
          blocks = Vector(
            BlockResultEntry(
              "test.md:10:1",
              "2.13.12",
              "val x = 1",
              success = true,
              skipped = false,
              Some("1"),
              Nil,
              None
            )
          )
        )

        val run2 = RunResults(
          scalaVersion = "3.3.3",
          sourceFile = "test.md",
          timestamp = 2000L,
          blocks = Vector(
            BlockResultEntry(
              "test.md:10:1",
              "3.3.3",
              "val x = 1",
              success = true,
              skipped = false,
              Some("1"),
              Nil,
              None
            )
          )
        )

        val merged = MergedResults.merge(Vector(run1, run2))

        assertTrue(
          merged.runs.size == 2,
          merged.blocksByLocation.size == 1,
          merged.blocksByLocation("test.md:10:1").size == 2,
          merged.versionsForBlock("test.md:10:1") == Vector("2.13.12", "3.3.3")
        )
      },

      test("isSuccess checks all processed blocks") {
        val run1 = RunResults(
          scalaVersion = "3.3.3",
          sourceFile = "test.md",
          timestamp = 1000L,
          blocks = Vector(
            BlockResultEntry(
              "test.md:10:1",
              "3.3.3",
              "val x = 1",
              success = true,
              skipped = false,
              None,
              Nil,
              None
            ),
            BlockResultEntry(
              "test.md:20:1",
              "3.3.3",
              "val y = 2",
              success = false,
              skipped = false,
              None,
              Nil,
              None
            )
          )
        )

        val merged = MergedResults.merge(Vector(run1))

        assertTrue(!merged.isSuccess)
      },

      test("skipped blocks don't affect isSuccess") {
        val run1 = RunResults(
          scalaVersion = "3.3.3",
          sourceFile = "test.md",
          timestamp = 1000L,
          blocks = Vector(
            BlockResultEntry(
              "test.md:10:1",
              "3.3.3",
              "val x = 1",
              success = true,
              skipped = false,
              None,
              Nil,
              None
            ),
            BlockResultEntry(
              "test.md:20:1",
              "3.3.3",
              "scala 2 only",
              success = false,
              skipped = true,
              None,
              Nil,
              None
            )
          )
        )

        val merged = MergedResults.merge(Vector(run1))

        assertTrue(merged.isSuccess)
      },

      test("fromJsonFiles parses and merges") {
        val run1 = RunResults(
          scalaVersion = "2.13.12",
          sourceFile = "test.md",
          timestamp = 1000L,
          blocks = Vector()
        )
        val run2 = RunResults(
          scalaVersion = "3.3.3",
          sourceFile = "test.md",
          timestamp = 2000L,
          blocks = Vector()
        )

        val json1 = run1.toJson
        val json2 = run2.toJson
        val merged = MergedResults.fromJsonFiles(Vector(json1, json2))

        assertTrue(
          merged.isRight,
          merged.toOption.get.runs.size == 2
        )
      }
    )
  )
