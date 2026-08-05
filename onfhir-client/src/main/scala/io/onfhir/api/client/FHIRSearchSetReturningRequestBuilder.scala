package io.onfhir.api.client

import io.onfhir.api.model.{FHIRRequest, FHIRResponse}

import scala.concurrent.{ExecutionContext, Future}

abstract class FHIRSearchSetReturningRequestBuilder(onFhirClient: IOnFhirClient, request: FHIRRequest)
  extends FhirSearchLikeRequestBuilder(onFhirClient, request)
    with IFhirBundleReturningRequestBuilder {

  /**
   *
   * @param fhirResponse
   * @return
   */
  override def constructBundle(fhirResponse: FHIRResponse): FHIRSearchSetBundle = {
    try {
      new FHIRSearchSetBundle(fhirResponse.responseBody.get, this)
    } catch {
      case e: Throwable =>
        throw FhirClientException("Invalid search result bundle!", Some(fhirResponse))
    }
  }


  /**
   * Send the FHIR search request and return the FHIR Bundle returned in the response parsed as FHIRSearchSetBundle if successfull, otherwise throw FhirClientException
   *
   * @param executionContext Execution context
   * @return
   */
  @throws[FhirClientException]
  def executeAndReturnBundle()(implicit executionContext: ExecutionContext): Future[FHIRSearchSetBundle] = {
    execute()
      .map(r => {
        if (r.httpStatus.isFailure() || r.responseBody.isEmpty)
          throw FhirClientException("Problem in FHIR search!", Some(r))
        constructBundle(r)
      })
  }

  /**
   * Returns Scala iterator where you can iterate over search results page by page
   *
   * @param executionContext Execution context
   * @return
   */
  def toIterator()(implicit executionContext: ExecutionContext): Iterator[Future[FHIRSearchSetBundle]] = {
    new SearchSetIterator(this)
  }

  /**
   * Send the FHIR request and paginate over the whole result set by retrieving next page until there is no further and merge them into FHIRSearchSetBundle
   *
   * @param executionContext
   * @return
   */
  def executeAndMergeBundle()(implicit executionContext: ExecutionContext): Future[FHIRSearchSetBundle] = {
    getMergedBundle(executeAndReturnBundle())
  }

  /**
   *
   * @param bundle
   * @param ec
   * @return
   */
  private def getMergedBundle(bundle: Future[FHIRSearchSetBundle])(implicit ec: ExecutionContext): Future[FHIRSearchSetBundle] = {
    bundle.flatMap {
      case r if r.hasNext() =>
        getMergedBundle(onFhirClient.next(r))
          .map { merged =>
            //Prepend this page so the merged results keep the server's page order;
            //the returned bundle stays the last page, so hasNext() is false on the merged result
            merged.searchResults = r.searchResults ++ merged.searchResults
            merged.includedResults = r.includedResults ++ merged.includedResults
            merged
          }
      case r =>
        Future.apply(r)
    }
  }
}


/**
 *
 * @param rb
 * @param executionContext
 */
class SearchSetIterator(rb: FHIRSearchSetReturningRequestBuilder)(implicit executionContext: ExecutionContext) extends Iterator[Future[FHIRSearchSetBundle]] {
  @volatile var latestBundle: Option[FHIRSearchSetBundle] = None

  override def hasNext: Boolean = latestBundle.forall(_.hasNext())

  override def next(): Future[FHIRSearchSetBundle] = {
    val page = latestBundle match {
      case None => rb.executeAndReturnBundle()
      case Some(b) => rb.onFhirClient.next(b)
    }
    //Record the page before completing the returned future so hasNext is
    //correct as soon as the caller observes the result
    page.map { bundle =>
      latestBundle = Some(bundle)
      bundle
    }
  }
}