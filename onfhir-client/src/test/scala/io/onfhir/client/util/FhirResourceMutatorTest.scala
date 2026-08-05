package io.onfhir.client.util

import io.onfhir.api.Resource
import io.onfhir.api.util.FHIRUtil
import io.onfhir.client.util.FhirResourceMutator._
import io.onfhir.util.JsonFormatter._
import org.json4s.JsonAST.{JArray, JObject, JString, JValue}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.io.Source

/**
 * Contract of the FHIR Patch like mutation helpers exposed to client users.
 */
@RunWith(classOf[JUnitRunner])
class FhirResourceMutatorTest extends Specification {

  private val observationSource =
    Source.fromInputStream(getClass.getResourceAsStream("/observation-glucose.json")).mkString

  private def observation: Resource = observationSource.parseJson

  private def identifierValues(resource: Resource): Seq[String] =
    (resource \ "identifier") match {
      case JArray(values) => values.map(value => (value \ "value").extract[String])
      case _ => Nil
    }

  private def elements(resource: Resource, name: String): Seq[JValue] =
    (resource \ name) match {
      case JArray(values) => values
      case other => Seq(other)
    }

  "FhirResourceMutator addElement" should {
    "add an element under an existing path" in {
      val updated: Resource = observation.addElement("Observation.code", "id", JString("code-1"))

      (updated \ "code" \ "id").extract[String] mustEqual "code-1"
    }

    "leave the resource untouched for a non matching path" in {
      val updated: Resource = observation.addElement("Observation.nonExisting", "id", JString("x"))

      updated mustEqual observation
    }

    "create an array when isArray is set" in {
      val updated: Resource =
        observation.addElement("Observation.code", "extension", JObject("url" -> JString("http://x")), isArray = true)

      (updated \ "code" \ "extension") must beAnInstanceOf[JArray]
      elements(updated, "code") // sanity: code is still a single element
      ((updated \ "code" \ "extension").asInstanceOf[JArray].arr.head \ "url").extract[String] mustEqual "http://x"
    }

    "append to the end of an already repetitive target element" in {
      val updated: Resource =
        observation.addRootElement("identifier", JObject("value" -> JString("99999")))

      identifierValues(updated) mustEqual Seq("6323", "81912", "99999")
    }

    "replace a non repetitive target element" in {
      val updated: Resource = observation.addRootElement("status", JString("amended"))

      (updated \ "status").extract[String] mustEqual "amended"
    }

    "throw for a non matching path in the OrThrowExc variant" in {
      observation.addElementOrThrowExc("Observation.nonExisting", "id", JString("x")) must
        throwA[IllegalArgumentException]
    }
  }

  "FhirResourceMutator addRootElement" should {
    "add a new element to the resource root" in {
      val updated: Resource = observation.addRootElement("language", JString("en"))

      (updated \ "language").extract[String] mustEqual "en"
    }

    "add a new repetitive element to the resource root" in {
      val updated: Resource = observation.addRootElement("category", JObject("text" -> JString("lab")), isArray = true)

      (updated \ "category") must beAnInstanceOf[JArray]
      ((updated \ "category").asInstanceOf[JArray].arr.head \ "text").extract[String] mustEqual "lab"
    }
  }

  "FhirResourceMutator insertElement" should {
    "insert at the head of a repetitive element" in {
      val updated: Resource =
        observation.insertElement("Observation.identifier", 0, JObject("value" -> JString("head")))

      identifierValues(updated) mustEqual Seq("head", "6323", "81912")
    }

    "insert in the middle of a repetitive element" in {
      val updated: Resource =
        observation.insertElement("Observation.identifier", 1, JObject("value" -> JString("middle")))

      identifierValues(updated) mustEqual Seq("6323", "middle", "81912")
    }

    "insert at the tail of a repetitive element" in {
      val updated: Resource =
        observation.insertElement("Observation.identifier", 2, JObject("value" -> JString("tail")))

      identifierValues(updated) mustEqual Seq("6323", "81912", "tail")
    }

    "reject an out of bounds index" in {
      observation.insertElement("Observation.identifier", 5, JObject("value" -> JString("x"))) must
        throwA[IndexOutOfBoundsException]
    }

    "reject insert on a non repetitive element" in {
      observation.insertElement("Observation.code", 0, JString("x")) must throwA[IllegalArgumentException]
    }

    "do nothing for a non matching path" in {
      val updated: Resource = observation.insertElement("Observation.nonExisting", 0, JString("x"))

      updated mustEqual observation
    }

    "throw for a non matching path in the OrThrowExc variant" in {
      observation.insertElementOrThrowExc("Observation.nonExisting", 0, JString("x")) must
        throwA[IllegalArgumentException]
    }
  }

  "FhirResourceMutator deleteElement" should {
    "delete a plain element" in {
      val updated: Resource = observation.deleteElement("Observation.status")

      (updated \ "status") mustEqual org.json4s.JsonAST.JNothing
    }

    "delete a single item of a repetitive element" in {
      val updated: Resource = observation.deleteElement("Observation.identifier[1]")

      identifierValues(updated) mustEqual Seq("6323")
    }

    "do nothing for a non matching path" in {
      val updated: Resource = observation.deleteElement("Observation.nonExisting")

      updated mustEqual observation
    }

    "throw for a non matching path in the OrThrowExc variant" in {
      observation.deleteElementOrThrowExc("Observation.nonExisting") must throwA[IllegalArgumentException]
    }
  }

  "FhirResourceMutator replaceElement" should {
    "replace a plain element" in {
      val updated: Resource = observation.replaceElement("Observation.status", JString("amended"))

      (updated \ "status").extract[String] mustEqual "amended"
    }

    "replace a single item of a repetitive element" in {
      val updated: Resource =
        observation.replaceElement("Observation.identifier[0]", JObject("value" -> JString("replaced")))

      identifierValues(updated) mustEqual Seq("replaced", "81912")
    }

    "do nothing for a non matching path" in {
      val updated: Resource = observation.replaceElement("Observation.nonExisting", JString("x"))

      updated mustEqual observation
    }

    "throw for a non matching path in the OrThrowExc variant" in {
      observation.replaceElementOrThrowExc("Observation.nonExisting", JString("x")) must
        throwA[IllegalArgumentException]
    }
  }

  "FhirResourceMutator moveElement" should {
    "move an item forward within a repetitive element" in {
      val updated: Resource = observation.moveElement("Observation.identifier", 0, 1)

      identifierValues(updated) mustEqual Seq("81912", "6323")
    }

    "move an item backward within a repetitive element" in {
      val updated: Resource = observation.moveElement("Observation.identifier", 1, 0)

      identifierValues(updated) mustEqual Seq("81912", "6323")
    }

    "reject an out of bounds source index" in {
      observation.moveElement("Observation.identifier", 5, 0) must throwA[IndexOutOfBoundsException]
    }

    "reject an out of bounds destination index" in {
      observation.moveElement("Observation.identifier", 0, 5) must throwA[IndexOutOfBoundsException]
    }

    "reject move on a non repetitive element" in {
      observation.moveElement("Observation.code", 0, 1) must throwA[IllegalArgumentException]
    }

    "do nothing for a non matching path" in {
      val updated: Resource = observation.moveElement("Observation.nonExisting", 0, 1)

      updated mustEqual observation
    }

    "throw for a non matching path in the OrThrowExc variant" in {
      observation.moveElementOrThrowExc("Observation.nonExisting", 0, 1) must throwA[IllegalArgumentException]
    }
  }

  "FhirResourceMutator" should {
    "chain mutations fluently and convert back to a plain resource" in {
      val updated: Resource =
        observation
          .addRootElement("language", JString("en"))
          .replaceElement("Observation.status", JString("amended"))
          .deleteElement("Observation.issued")
          .insertElement("Observation.identifier", 0, JObject("value" -> JString("first")))

      (updated \ "language").extract[String] mustEqual "en"
      (updated \ "status").extract[String] mustEqual "amended"
      (updated \ "issued") mustEqual org.json4s.JsonAST.JNothing
      identifierValues(updated) mustEqual Seq("first", "6323", "81912")
      FHIRUtil.extractResourceType(updated) mustEqual "Observation"
    }

    "not mutate the resource the wrapper was created from" in {
      val original = observation
      val wrapper = convertToFhirMutableResource(original)
      wrapper.addRootElement("language", JString("en"))

      (original \ "language") mustEqual org.json4s.JsonAST.JNothing
      (convertToResource(wrapper) \ "language").extract[String] mustEqual "en"
    }
  }
}
