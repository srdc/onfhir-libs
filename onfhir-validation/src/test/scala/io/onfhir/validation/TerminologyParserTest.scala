package io.onfhir.validation

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TerminologyParserTest extends Specification {
  import ValidationTestFixtures.resource

  "TerminologyParser" should {
    "preserve explicit included and excluded codes in a versioned ValueSet" in {
      val systemUrl = "http://example.org/CodeSystem/test"
      val valueSetUrl = "http://example.org/ValueSet/test"
      val valueSet = resource(
        s"""{
           |  "resourceType":"ValueSet",
           |  "url":"$valueSetUrl",
           |  "version":"1.0.0",
           |  "compose":{
           |    "include":[{"system":"$systemUrl","concept":[{"code":"allowed"},{"code":"also-allowed"}]}],
           |    "exclude":[{"system":"$systemUrl","concept":[{"code":"excluded"}]}]
           |  }
           |}""".stripMargin
      )

      val parsed = new TerminologyParser().parseValueSetBundle(Seq(valueSet))
      val restrictions = parsed(valueSetUrl)("1.0.0")

      restrictions.includes.codes(systemUrl) mustEqual Set("allowed", "also-allowed")
      restrictions.excludes must beSome
      restrictions.excludes.get.codes(systemUrl) mustEqual Set("excluded")
    }
  }
}
