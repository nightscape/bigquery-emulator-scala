package dev.mauch.zetasql

import java.sql.{Connection, DriverManager, ResultSet}
import com.google.zetasql.*
import parser.ASTNodes
import _root_.com.google.zetasql.parser.ASTNodes.ASTStatement

object BigQueryEmulator {
  val duckDBCatalog = new DuckDBCatalog()
  // Initialize an in-memory DuckDB connection
  private val conn: Connection = {
    Class.forName("org.duckdb.DuckDBDriver")
    DriverManager.getConnection("jdbc:duckdb:")
  }

  def runQuery(bigQuerySql: String): Unit = {
    val languageOptions = new LanguageOptions()
    val ast: ASTStatement = Parser.parseStatement(bigQuerySql, languageOptions)

    // Transform the ZetaSQL AST into something DuckDB understands
    val duckDbSql = new QueryRewriter(duckDBCatalog).rewriteDdlStatement(ast)

    println(s"[Debug] DuckDB-compatible SQL: $duckDbSql")

    // Execute against DuckDB
    val stmt = conn.createStatement()
    val rs = stmt.executeQuery(duckDbSql)
    // Process the ResultSet
    while (rs.next()) {
      val row = (1 to rs.getMetaData.getColumnCount).map(i => rs.getString(i))
      println(row.mkString(" | "))
    }
    rs.close()
    stmt.close()
  }

  def main(args: Array[String]): Unit = {
    // Example setup: create table in DuckDB
    val stmt = conn.createStatement()
    stmt.execute("CREATE TABLE sample_table (id INT, name STRING)")
    stmt.execute("INSERT INTO sample_table VALUES (1, 'Alice'), (2, 'Bob'), (11, 'Charlie')")
    stmt.close()

    // Now run a "BigQuery style" query
    val query = "SELECT id, name FROM sample_table WHERE SAFE_DIVIDE(id, 0) > 1"
    runQuery(query)
  }
}
