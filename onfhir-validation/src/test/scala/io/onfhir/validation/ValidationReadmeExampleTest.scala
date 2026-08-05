package io.onfhir.validation

import io.onfhir.api.validation.ConstraintKeys
import io.onfhir.config.BaseFhirConfig
import org.json4s.JsonAST.JObject
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.ExecutionContext.Implicits.global

@RunWith(classOf[JUnitRunner])
class ValidationReadmeExampleTest extends Specification {
  import ValidationTestFixtures._

  "README validation example" should {
    "compile and validate content against a populated configuration" in {
      val patientProfileUrl = "http://hl7.org/fhir/StructureDefinition/Patient"
      val patientProfile = profile(
        url = patientProfileUrl,
        resourceType = "Patient",
        elementRestrictions = Seq(element("active", Map(
          ConstraintKeys.DATATYPE -> TypeRestriction(Seq("boolean" -> Nil))
        ), profileDefinedIn = patientProfileUrl))
      )
      val configured: BaseFhirConfig = config(Seq(patientProfile), resourceTypes = Set("Patient"))
      val patient: JObject = resource("""{"resourceType":"Patient","active":true}""")

      def validatePatient(config: BaseFhirConfig, content: JObject) = {
        val validator = FhirValidator(config)
        validator.validateResource(content)
      }

      awaitResult(validatePatient(configured, patient)) must beEmpty
    }
  }
}
