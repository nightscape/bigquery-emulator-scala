package dev.mauch.bqemulator

import kyo.*
import sttp.model.StatusCode


trait Handler:

  // Dataset operations
  def createDataset(projectId: String, prettyPrint: Option[Boolean], dataset: Dataset): Dataset < (Abort[ErrorResponse] & Async)
  def getDataset(
    projectId: String,
    datasetId: String,
    accessPolicyVersion: Option[Int]
  ): Dataset < (Abort[ErrorResponse] & Async)
  def listDatasets(projectId: String): ListDatasets200response < (Abort[ErrorResponse] & Async)
  def deleteDataset(
    projectId: String,
    datasetId: String,
    deleteContents: Option[Boolean]
  ): Unit < (Abort[ErrorResponse] & Async)

  // Table operations
  def createTable(projectId: String, datasetId: String, table: Table): Table < (Abort[ErrorResponse] & Async)
  def getTable(projectId: String, datasetId: String, tableId: String): Table < (Abort[ErrorResponse] & Async)
  def listTables(projectId: String, datasetId: String): ListTables200response < (Abort[ErrorResponse] & Async)
  def deleteTable(projectId: String, datasetId: String, tableId: String): Unit < (Abort[ErrorResponse] & Async)

  // Job operations
  def createJob(projectId: String, job: Job): Job < (Abort[ErrorResponse] & Async)
  def getJob(projectId: String, jobId: String): Job < (Abort[ErrorResponse] & Async)

  // Query operations
  def runQuery(projectId: String, query: QueryRequest): QueryResponse < (Abort[ErrorResponse] & Async)

end Handler

object Handler:

  import dev.mauch.zetasql.*
  val init: Handler < Env[DuckDB] = defer {
    val duckdb = Env.get[DuckDB].now
    Live(duckdb)
  }

  final class Live(duckdb: DuckDB) extends Handler:
    import ErrorInfo._
    private def notFound(tpe: String, id: Any) = Abort.fail[ErrorResponse](ErrorResponse(StatusNotFound(s"${tpe} ${id} not found")))
    private def unprocessableEntity[T](tpe: String, t: T) = Abort.fail[ErrorResponse](ErrorResponse(StatusBadRequest(s"Unprocessable ${tpe}: ${t}")))
    private def internalServerError(e: Throwable) = Abort.fail[ErrorResponse](ErrorResponse(StatusInternalServerError(s"Internal Server Error $e")))

    // Dataset operations
    def createDataset(projectId: String, prettyPrint: Option[Boolean], dataset: Dataset): Dataset < (Abort[ErrorResponse] & Async) = defer {
      val datasetId: String = Kyo.fromOption(dataset.datasetReference.flatMap(_.datasetId)).now

      duckdb
        .createSchema(datasetId)
        .mapAbort(_ =>
          internalServerError(new Exception("Internal Server Error"))
        )
        .map(_ => dataset)
        .now
    }.mapAbort { a =>
      unprocessableEntity("Dataset", "Dataset ID is required")
    }

    def getDataset(
      projectId: String,
      datasetId: String,
      accessPolicyVersion: Option[Int]
    ): Dataset < (Abort[ErrorResponse] & Async) = defer {
      val schema = duckdb.getSchema(datasetId).mapAbort(e => internalServerError(e)).now
      if (schema.isEmpty) notFound("Dataset", datasetId).now
      else
        Dataset(
          datasetReference = Some(DatasetReference(projectId = Some(projectId), datasetId = Some(datasetId))),
          id = Some(datasetId),
          location = None,
          friendlyName = Some("friendlyName"),
          creationTime = Some("creationTime")
        )
    }

    def listDatasets(projectId: String): ListDatasets200response < (Abort[ErrorResponse] & Async) = defer {
      val schemas = duckdb.listSchemas.now
      ListDatasets200response(datasets =
        Some(
          schemas.map(schema =>
            Dataset(
              datasetReference = Some(DatasetReference(projectId = Some(projectId), datasetId = Some(schema.name))),
              id = Some(schema.name),
              location = Some("US"),
              friendlyName = Some(schema.name)
            )
          )
        )
      )

    }.mapAbort(_ =>
      internalServerError(new Exception("Internal Server Error"))
    )

    def deleteDataset(
      projectId: String,
      datasetId: String,
      deleteContents: Option[Boolean]
    ): Unit < (Abort[ErrorResponse] & Async) =
      duckdb.dropSchema(datasetId).mapAbort {
        case _: NotFound =>
          notFound("Dataset", datasetId)
        case e =>
          internalServerError(e)
      }

    // Table operations
    def createTable(projectId: String, datasetId: String, table: Table): Table < (Abort[ErrorResponse] & Async) =
      table.copy(expirationTime = Some("NOW!"))

    def getTable(projectId: String, datasetId: String, tableId: String): Table < (Abort[ErrorResponse] & Async) = ???

    def listTables(projectId: String, datasetId: String): ListTables200response < (Abort[ErrorResponse] & Async) = ???

    def deleteTable(projectId: String, datasetId: String, tableId: String): Unit < (Abort[ErrorResponse] & Async) = ???

    // Job operations
    def createJob(projectId: String, job: Job): Job < (Abort[ErrorResponse] & Async) = ???

    def getJob(projectId: String, jobId: String): Job < (Abort[ErrorResponse] & Async) = ???

    // Query operations
    def runQuery(projectId: String, query: QueryRequest): QueryResponse < (Abort[ErrorResponse] & Async) = ???
  end Live
end Handler
