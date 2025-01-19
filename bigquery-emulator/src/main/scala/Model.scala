package dev.mauch.bqemulator

import sttp.tapir.Schema
import sttp.tapir.model._
import sttp.tapir.generic.auto._
import sttp.tapir.json.upickle._
import upickle.default._
import sttp.model.StatusCode

case class JobReference (
  projectId: Option[String] = None,
  jobId: Option[String] = None
) derives ReadWriter, Schema

case class ErrorContainer(errors: Seq[ErrorInfo]) derives ReadWriter, Schema
case class ErrorResponse(error: ErrorContainer) derives ReadWriter, Schema
object ErrorResponse {
  def apply(errors: ErrorInfo*): ErrorResponse = ErrorResponse(ErrorContainer(errors))
}

case class ErrorInfo(val code: Int, message: String, reason: String) derives ReadWriter
object ErrorInfo {
  /*
  'notFound':
  'duplicate':
  'accessDenied':
  'invalidQuery':
  'termsOfServiceNotAccepted':
  'backendError':
  */
  def StatusNotFound(message: String) = ErrorInfo(404, message, "notFound")
  def StatusBadRequest(message: String) = ErrorInfo(400, message, "badRequest")
  def StatusUnauthorized(message: String) = ErrorInfo(401, message, "unauthorized")
  def StatusInternalServerError(message: String) = ErrorInfo(500, message, "backendError")
  def StatusNoContent = ErrorInfo(204, "", "")
}


case class ErrorDetailsItem (

) derives ReadWriter, Schema

case class Dataset (
  id: Option[String] = None,
  location: Option[String] = None,
  friendlyName: Option[String] = None,
  creationTime: Option[String] = None,
  kind: Option[String] = None,
  datasetReference: Option[DatasetReference] = None
) derives ReadWriter, Schema

case class ExtractConfig (
  destinationUris: Option[Seq[String]] = None,
  sourceTable: Option[TableReference] = None,
  destinationFormat: Option[ExtractConfigDestinationFormat] = None
) derives ReadWriter, Schema

enum ExtractConfigDestinationFormat derives ReadWriter, Schema {
  case CSV, NEWLINE_DELIMITED_JSON, AVRO, PARQUET
}

case class Configuration (
  queryConfig: Option[QueryConfig] = None,
  loadConfig: Option[LoadConfig] = None,
  extractConfig: Option[ExtractConfig] = None
) derives ReadWriter, Schema

case class QueryRequest (
  useQueryCache: Option[Boolean] = None,
  defaultDataset: Option[DatasetReference] = None,
  dryRun: Option[Boolean] = None,
  query: String,
  maxResults: Option[Int] = None,
  useLegacySql: Option[Boolean] = None
) derives ReadWriter, Schema

case class TableReference (
  projectId: Option[String] = None,
  datasetId: Option[String] = None,
  tableId: Option[String] = None
) derives ReadWriter, Schema

case class Job (
  jobReference: Option[JobReference] = None,
  configuration: Option[Configuration] = None,
  status: Option[Status] = None
) derives ReadWriter, Schema

case class DatasetReference (
  projectId: Option[String] = None,
  datasetId: Option[String] = None
) derives ReadWriter, Schema

case class TableFieldSchema (
  name: Option[String] = None,
  `type`: Option[TableFieldSchemaType] = None,
  mode: Option[TableFieldSchemaMode] = None,
  fields: Option[Seq[TableFieldSchema]] = None
)
object TableFieldSchema {
  implicit def rw: ReadWriter[TableFieldSchema] = macroRW
  implicit def schema: Schema[TableFieldSchema] = Schema.derived
}

enum TableFieldSchemaType derives ReadWriter, Schema {
  case STRING, INTEGER, FLOAT, BOOLEAN, TIMESTAMP, RECORD, DATE
}

enum TableFieldSchemaMode derives ReadWriter, Schema {
  case NULLABLE, REQUIRED, REPEATED
}

case class ListDatasets200response (
  datasets: Option[Seq[Dataset]] = None
) derives ReadWriter, Schema

case class QueryConfig (
  useQueryCache: Option[Boolean] = None,
  maximumBytesBilled: Option[String] = None,
  destinationTable: Option[TableReference] = None,
  query: Option[String] = None,
  useLegacySql: Option[Boolean] = None
) derives ReadWriter, Schema

case class Table (
  `type`: Option[TableType] = None,
  schema: Option[TableSchema] = None,
  tableReference: Option[TableReference] = None,
  creationTime: Option[String] = None,
  lastModifiedTime: Option[String] = None,
  expirationTime: Option[String] = None
) derives ReadWriter, Schema

enum TableType derives ReadWriter, Schema {
  case TABLE, VIEW, EXTERNAL, MATERIALIZED_VIEW, SNAPSHOT
}

case class ListTables200response (
  tables: Option[Seq[Table]] = None
) derives ReadWriter, Schema

case class ErrorProto (
  reason: Option[String] = None,
  location: Option[String] = None,
  debugInfo: Option[String] = None,
  message: Option[String] = None
) derives ReadWriter, Schema

case class QueryResponse (
  schema: Option[TableSchema] = None,
  rows: Option[Seq[QueryResponseRowsItem]] = None,
  errors: Option[Seq[ErrorProto]] = None,
  jobComplete: Option[Boolean] = None,
  totalRows: Option[String] = None
) derives ReadWriter, Schema

case class QueryResponseRowsItem (

) derives ReadWriter, Schema

case class TableSchema (
  fields: Seq[TableFieldSchema] = Seq.empty
) derives ReadWriter, Schema

case class Status (
  state: Option[StatusState] = None,
  errorResult: Option[ErrorProto] = None
) derives ReadWriter, Schema

enum StatusState derives ReadWriter {
  case PENDING, RUNNING, DONE
}

case class LoadConfig (
  sourceUris: Option[Seq[String]] = None,
  createDisposition: Option[LoadConfigCreateDisposition] = None,
  schema: Option[TableSchema] = None,
  destinationTable: Option[TableReference] = None,
  writeDisposition: Option[LoadConfigWriteDisposition] = None
) derives ReadWriter, Schema

enum LoadConfigCreateDisposition derives ReadWriter {
  case CREATE_IF_NEEDED, CREATE_NEVER
}

enum LoadConfigWriteDisposition derives ReadWriter {
  case WRITE_TRUNCATE, WRITE_APPEND, WRITE_EMPTY
}
