package dev.mauch.zetasql

import com.google.zetasql.*, parser.ASTNodes.*
import scala.jdk.CollectionConverters.*

class QueryRewriter(duckDBCatalog: DuckDBCatalog) {
  def rewriteDdlStatement(ddl: ASTStatement): String = ddl match {
    case createStmt: ASTCreateSchemaStatement =>
      val schemaName = createStmt.getName
        .getNames()
        .asScala
        .mkString(".") // e.g. ["myproject", "mydataset"]
      // Convert that into a single DuckDB schema name
      val duckSchema = flattenProjectAndDataset(schemaName)
      duckDBCatalog.createSchema(duckSchema)
      ""

    case createTableStmt: ASTCreateTableStatement =>
      // Similar logic for create table
      val tablePath =
        createTableStmt.getName
          .getNames()
          .asScala // e.g. ["myproject", "mydataset", "mytable"]
      val duckSchema = flattenProjectAndDataset(tablePath.take(tablePath.size - 1).mkString("."))
      val tableName = tablePath.last
      val columns = extractColumnsFromAst(createTableStmt)
      duckDBCatalog.createTable(duckSchema, tableName.toString(), columns)
      ""

    // ... and so on for other DDL statements
    case ddl =>
      throw new UnsupportedOperationException(s"DDL statement not yet supported: $ddl")
  }

  private def flattenProjectAndDataset(pathString: String): String = {
    // e.g. "myproject.mydataset" -> "myproject_mydataset"
    pathString.replace('.', '_')
  }

  private def extractColumnsFromAst(stmt: ASTCreateTableStatement): Map[String, String] = {
    // Walk the AST or resolved AST to figure out columns and types.
    // For demonstration, return an empty map or stub it out.
    Map.empty
  }
}
