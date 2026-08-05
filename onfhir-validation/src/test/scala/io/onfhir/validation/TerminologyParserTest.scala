package io.onfhir.validation

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TerminologyParserTest extends Specification {
  import ValidationTestFixtures.resource

  private val hierarchySystemUrl = "http://example.org/CodeSystem/hierarchy"
  private val hierarchyValueSetUrl = "http://example.org/ValueSet/hierarchy"

  /**
   * CodeSystem used by the filter examples, with the concept hierarchy
   *
   *  animal              mineral
   *  |-- mammal
   *  |   |-- dog
   *  |   |-- cat
   *  |-- bird
   *      |-- parrot
   */
  private val hierarchyCodeSystem = resource(
    s"""{
       |  "resourceType":"CodeSystem",
       |  "url":"$hierarchySystemUrl",
       |  "version":"1.0.0",
       |  "hierarchyMeaning":"is-a",
       |  "concept":[
       |    {
       |      "code":"animal",
       |      "concept":[
       |        {"code":"mammal","concept":[{"code":"dog"},{"code":"cat"}]},
       |        {"code":"bird","concept":[{"code":"parrot"}]}
       |      ]
       |    },
       |    {"code":"mineral"}
       |  ]
       |}""".stripMargin
  )

  private val allHierarchyCodes = Set("animal", "mammal", "dog", "cat", "bird", "parrot", "mineral")

  /**
   * Expand a ValueSet including the concepts of the hierarchy CodeSystem selected by the given filters
   * @param filters  Filters as property, operator and value
   * @return         Codes included for the hierarchy CodeSystem
   */
  private def expandedCodes(filters: (String, String, String)*): Set[String] = {
    val filterPart =
      if (filters.isEmpty) ""
      else filters.map(f => s"""{"property":"${f._1}","op":"${f._2}","value":"${f._3}"}""").mkString(""","filter":[""", ",", "]")
    val valueSet = resource(
      s"""{
         |  "resourceType":"ValueSet",
         |  "url":"$hierarchyValueSetUrl",
         |  "version":"1.0.0",
         |  "compose":{"include":[{"system":"$hierarchySystemUrl"$filterPart}]}
         |}""".stripMargin
    )

    new TerminologyParser()
      .parseValueSetBundle(Seq(hierarchyCodeSystem, valueSet))
      .get(hierarchyValueSetUrl)
      .flatMap(_.get("1.0.0"))
      .flatMap(_.includes.codes.get(hierarchySystemUrl))
      .getOrElse(Set.empty)
  }

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

    "include every concept of a CodeSystem when the include has no filter" in {
      expandedCodes() mustEqual allHierarchyCodes
    }

    "expand an 'is-a' filter to the concept and its descendants" in {
      expandedCodes(("concept", "is-a", "mammal")) mustEqual Set("mammal", "dog", "cat")
      expandedCodes(("concept", "is-a", "animal")) mustEqual allHierarchyCodes - "mineral"
    }

    "expand a 'descendent-of' filter to the descendants without the concept itself" in {
      expandedCodes(("concept", "descendent-of", "mammal")) mustEqual Set("dog", "cat")
      expandedCodes(("concept", "descendent-of", "animal")) mustEqual Set("mammal", "dog", "cat", "bird", "parrot")
    }

    //'descendant-of' is not a FHIR filter operator; it must be ignored like any other unsupported one and must not throw
    "not support the misspelled 'descendant-of'" in {
      expandedCodes(("concept", "descendant-of", "animal")) mustEqual allHierarchyCodes
    }

    "expand a 'generalizes' filter to the concept and its ancestors" in {
      expandedCodes(("concept", "generalizes", "dog")) mustEqual Set("dog", "mammal", "animal")
      expandedCodes(("concept", "generalizes", "animal")) mustEqual Set("animal")
    }

    "expand an 'is-not-a' filter to the concepts outside the given subtree" in {
      expandedCodes(("concept", "is-not-a", "mammal")) mustEqual Set("animal", "bird", "parrot", "mineral")
      expandedCodes(("concept", "is-not-a", "animal")) mustEqual Set("mineral")
    }

    "combine a hierarchy filter with a property filter" in {
      expandedCodes(("concept", "is-a", "animal"), ("code", "regex", "^b.*")) mustEqual Set("bird")
    }

    //Documents the parser limitations recorded in onfhir-validation/README.md
    "ignore an unsupported filter operator and any hierarchy filter after the first" in {
      expandedCodes(("concept", "child-of", "animal")) mustEqual allHierarchyCodes
      expandedCodes(("concept", "is-a", "mammal"), ("concept", "is-a", "bird")) mustEqual Set("mammal", "dog", "cat")
    }
  }
}
