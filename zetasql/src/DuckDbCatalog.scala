package dev.mauch.zetasql

import java.sql.DriverManager
import scala.collection.mutable.ArrayBuffer

case class TableInfo(
  tableName: String,
  columns: Map[String, String] // colName -> type
)

case class SchemaInfo(
  schemaName: String,
  tables: scala.collection.mutable.Map[String, TableInfo]
)

class DuckDBCatalog {
  // Key = schemaName (i.e., "project_dataset" or just "dataset")
  private val schemas =
    scala.collection.mutable.Map[String, SchemaInfo]()

  def createSchema(schemaName: String): Unit = {
    // Also instruct DuckDB to create the schema
    executeDuckDBStatement(s"CREATE SCHEMA IF NOT EXISTS $schemaName")

    // Maintain it in memory
    if (!schemas.contains(schemaName)) {
      schemas(schemaName) = SchemaInfo(schemaName, scala.collection.mutable.Map.empty)
    }
  }

  def createTable(schemaName: String, tableName: String, columns: Map[String, String]): Unit = {
    // Make sure schema exists in memory
    if (!schemas.contains(schemaName)) {
      createSchema(schemaName)
    }
    // Record the table info
    val tableInfo = TableInfo(tableName, columns)
    schemas(schemaName).tables(tableName) = tableInfo

    // Build DuckDB DDL and execute
    val columnsDdl = columns.map { case (colName, colType) => s"$colName $colType" }.mkString(", ")
    val ddl = s"CREATE TABLE IF NOT EXISTS $schemaName.$tableName ($columnsDdl);"
    executeDuckDBStatement(ddl)
  }

  def getSchema(schemaName: String): Option[SchemaInfo] = schemas.get(schemaName)

  def schemaExists(schemaName: String): Boolean = schemas.contains(schemaName)

  private def executeDuckDBStatement(sql: String): Unit = {
    Class.forName("org.duckdb.DuckDBDriver");
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    val stmt = conn.createStatement()
    stmt.execute(sql)
    stmt.close()
    conn.close()
  }
}

import kyo.*
import java.sql.{Connection, DriverManager}

class NotFound(message: String) extends Exception(message)

object DuckDB:
    case class Schema(name: String)

    def init: DuckDB < Abort[Throwable] = defer {
            Kyo.attempt(Class.forName("org.duckdb.DuckDBDriver")).now
            val connection = Kyo.attempt(DriverManager.getConnection("jdbc:duckdb:")).now
            Live(connection)


    }

    final class Live(connection: Connection) extends DuckDB:

        def getSchema(schemaName: String): Option[Schema] < (Abort[Throwable] & Async) = defer {

            val stmt = Kyo.attempt(connection.createStatement()).now
            val rs = Kyo.attempt(stmt.executeQuery(
                    s"SELECT schema_name FROM information_schema.schemata WHERE schema_name = '$schemaName'"
                )).now
            if (rs.next()) {
                Some(Schema(rs.getString("schema_name")))
            } else {
                None
            }
        }

        def createSchema(schemaName: String): Unit < (Abort[Throwable] & Async) = defer {
            val stmt = Kyo.attempt(connection.createStatement()).now
            Kyo.attempt(stmt.execute(s"CREATE SCHEMA IF NOT EXISTS $schemaName")).now
        }

        def dropSchema(schemaName: String): Unit < (Abort[Throwable] & Async) = defer {
            val stmt = Kyo.attempt(connection.createStatement()).now
            Kyo.attempt(stmt.execute(s"DROP SCHEMA IF EXISTS $schemaName CASCADE")).now
        }

        def listSchemas: List[Schema] < (Abort[Throwable] & Async) = defer {
            val stmt = Kyo.attempt(connection.createStatement()).now
            val rs = Kyo.attempt(stmt.executeQuery(
                    "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN ('information_schema', 'pg_catalog')"
                )).now
            val schemas = ArrayBuffer.empty[Schema]
            while (rs.next()) {
                schemas += Schema(rs.getString("schema_name"))
            }
            schemas.toList
        }
    end Live
end DuckDB

trait DuckDB:
    import DuckDB.*
    def getSchema(schemaName: String): Option[Schema] < (Abort[Throwable] & Async)
    def createSchema(schemaName: String): Unit < (Abort[Throwable] & Async)
    def dropSchema(schemaName: String): Unit < (Abort[Throwable] & Async)
    def listSchemas: List[Schema] < (Abort[Throwable] & Async)

end DuckDB
