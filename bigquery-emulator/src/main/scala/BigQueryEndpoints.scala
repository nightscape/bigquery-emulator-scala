package dev.mauch.bqemulator

import sttp.tapir._
import sttp.tapir.files._
import sttp.tapir.model._
import sttp.tapir.generic.auto._
import sttp.tapir.json.upickle._
import sttp.model.StatusCode

object BigQueryEndpoints {
  // here we are defining an error output, but the same can be done for regular outputs
  import ErrorInfo._
  val baseEndpoint = endpoint.errorOut(
    oneOf[ErrorResponse](
      oneOfVariant(statusCode(StatusCode.NotFound).and(jsonBody[ErrorResponse].description("not found"))),
      oneOfVariant(statusCode(StatusCode.BadRequest).and(jsonBody[ErrorResponse].description("bad request"))),
      oneOfVariant(statusCode(StatusCode.Unauthorized).and(jsonBody[ErrorResponse].description("unauthorized"))),
      //oneOfVariant(statusCode(StatusCode.NoContent).and(emptyOutputAs(StatusNoContent))),
      oneOfDefaultVariant(jsonBody[ErrorResponse].description("internal server error"))
    )
  )
  val basePath = "bigquery" / "v2" / "projects" / path[String]("projectId")

  lazy val createJob =
    baseEndpoint.post
      .in(basePath / "jobs")
      .in(jsonBody[Job])
      .out(jsonBody[Job].description("Job created successfully"))

  lazy val runQuery =
    baseEndpoint.post
      .in(basePath / "queries")
      .in(jsonBody[QueryRequest])
      .out(jsonBody[QueryResponse].description("Query results"))

  lazy val getDataset =
    baseEndpoint.get
      .in(basePath / "datasets" / path[String]("datasetId") / query[Option[Int]]("accessPolicyVersion"))
      .out(jsonBody[Dataset].description("Successful response"))

  lazy val deleteDataset =
    baseEndpoint.delete
      .in(basePath / "datasets" / path[String]("datasetId"))
      .in(query[Option[Boolean]]("deleteContents").description("If true, delete all the tables in the dataset"))

  lazy val getTable =
    baseEndpoint.get
      .in(basePath / "datasets" / path[String]("datasetId") / "tables" / path[String]("tableId"))
      .out(jsonBody[Table].description("Successful response"))

  lazy val deleteTable =
    baseEndpoint.delete
      .in(basePath / "datasets" / path[String]("datasetId") / "tables" / path[String]("tableId"))

  lazy val listDatasets =
    baseEndpoint.get
      .in(basePath / "datasets")
      .out(jsonBody[ListDatasets200response].description("Successful response"))

  lazy val createDataset =
    baseEndpoint.post
      .in(basePath / "datasets" / query[Option[Boolean]]("prettyPrint"))
      .in(jsonBody[Dataset])
      .out(jsonBody[Dataset].description("Dataset created successfully"))

  lazy val getJob =
    baseEndpoint.get
      .in(basePath / "jobs" / path[String]("jobId"))
      .out(jsonBody[Job].description("Successful response"))

  lazy val listTables =
    baseEndpoint.get
      .in(basePath / "datasets" / path[String]("datasetId") / "tables")
      .out(jsonBody[ListTables200response].description("Successful response"))

  lazy val createTable =
    baseEndpoint.post
      .in(basePath / "datasets" / path[String]("datasetId") / "tables")
      .in(jsonBody[Table])
      .out(jsonBody[Table].description("Table created successfully"))

  lazy val discovery =
    baseEndpoint.get
      .in("$discovery" / "rest" / query[Int]("version"))
      .out(stringJsonBody)

  lazy val generatedEndpoints = List(
    createJob,
    runQuery,
    getDataset,
    deleteDataset,
    getTable,
    deleteTable,
    listDatasets,
    createDataset,
    getJob,
    listTables,
    createTable
  )

}

import java.time.Instant
import kyo.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*

object KyoEndpoints {
  import BigQueryEndpoints.*
  val init: Unit < (Env[Handler] & Routes) = defer {
    val handler = Env.get[Handler].now

    // Health and discovery endpoints
    Routes
      .add(
        _.get
          .in("health")
          .out(stringBody)
      )(_ => "ok")
      .now

    // BigQuery endpoints
    Routes.add(createJob)(handler.createJob).now
    Routes.add(runQuery)(handler.runQuery).now
    Routes.add(getDataset)(handler.getDataset).now
    Routes.add(deleteDataset)(handler.deleteDataset).now
    Routes.add(getTable)(handler.getTable).now
    Routes.add(deleteTable)(handler.deleteTable).now
    Routes.add(listDatasets)(handler.listDatasets).now
    Routes.add(createDataset)(handler.createDataset).now
    Routes.add(getJob)(handler.getJob).now
    Routes.add(listTables)(handler.listTables).now
    Routes.add(createTable)(handler.createTable).now
    Emit
      .value(
        Route(staticResourceGetServerEndpoint("$discovery" / "rest")(this.getClass.getClassLoader, "discovery.json"))
      )
      .unit.now
  }
}
