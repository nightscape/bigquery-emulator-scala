package dev.mauch.bqemulator

import hedgehog._
import hedgehog.runner._
import hedgehog.state._

import com.google.cloud.bigquery.{Range => _, _}
import com.google.cloud.http.HttpTransportOptions
import com.google.api.gax.retrying.RetrySettings
import com.google.cloud.NoCredentials
import kyo.{Result => _, Var => _, _}
import hedgehog.core.PropertyConfig
import hedgehog.core.Seed
import dev.mauch.zetasql.DuckDB
import scala.collection.JavaConverters._

object IntegrationTests extends KyoApp {
  println("Starting tests class")
  import Server.*

  run {
    defer {
      println("Starting tests")
      val config = PropertyConfig.default
      val seedSource = SeedSource.fromEnvOrTime()
      val seed = Seed.fromLong(seedSource.seed)
      println(seedSource.renderLog)
      val port: Int = System.property[Int]("PORT", 9050).now
      println(s"Starting server on port $port")
      val duckdb = DuckDB.init.now
      val handler = Env.run(duckdb)(Handler.init).now
      val handledEndpoints = Env.run(handler)(KyoEndpoints.init).later
      val nettyBinding = Routes.run(server.port(port))(handledEndpoints).now
      println("Server started")
      tests.foreach(t => {
        val report = Property.check(t.withConfig(config), t.result, seed)
        println(Test.renderReport(this.getClass.getName, t, report, ansiCodesSupported = true))
      })
      nettyBinding.stop().now
      println("Server stopped")
    }
  }
  def tests: List[Prop] =
    List(property("dataset operations", testDatasetOperations))

  // Setup BigQuery client pointing to emulator
  def createEmulatorClient(): BigQuery = {
    val options = BigQueryOptions
      .newBuilder()
      .setHost("http://localhost:9050")
      .setProjectId("test-project")
      .setCredentials(NoCredentials.getInstance())
      .setTransportOptions(HttpTransportOptions.newBuilder().setConnectTimeout(10000).build())
      .build()
    options.getService()
  }

  case class State(datasets: Set[String], tables: Map[String, Set[String]])

  object State {
    def empty: State = State(Set.empty, Map.empty)
  }

  // Commands
  case class CreateDataset(datasetId: String, location: Option[String], description: String)
  case class GetDataset(datasetId: String)
  case class ListDatasets()
  case class DeleteDataset(datasetId: String)

  // Add new commands for tables
  case class CreateTable(datasetId: String, tableId: String, schema: Schema)
  case class GetTable(datasetId: String, tableId: String)
  case class ListTables(datasetId: String)
  case class DeleteTable(datasetId: String, tableId: String)

  def genDatasetId: Gen[String] = for {
    c <- Gen.lower
    s <- Gen.string(Gen.alphaNum, Range.linear(1, 15))
  } yield c +: s

  def genLocation: Gen[Option[String]] =
    Gen.element(None, List()) // "US", List("EU", "ASIA"))

  def genDescription: Gen[String] =
    Gen.string(Gen.unicode, Range.linear(1, 100))

  def genTableId: Gen[String] = for {
    c <- Gen.lower
    s <- Gen.string(Gen.alphaNum, Range.linear(1, 15))
  } yield c +: s

  def genSchema: Gen[Schema] = for {
    fields <- Gen.list(genField, Range.linear(1, 5))
  } yield Schema.of(fields*)

  def genField: Gen[Field] = for {
    name <- genTableId
    tpe <- Gen.element(StandardSQLTypeName.STRING, List(StandardSQLTypeName.INT64, StandardSQLTypeName.FLOAT64))
  } yield Field.of(name, tpe)

  def commands(bq: BigQuery): List[CommandIO[State]] =
    List(
      commandCreateDataset(bq),
      commandDeleteDataset(bq),
      commandListDatasets(bq),
      commandCreateTable(bq),
      commandGetTable(bq),
      commandDeleteTable(bq),
      commandListTables(bq)
    )

  def testDatasetOperations: Property = {
    val bq = createEmulatorClient()
    sequential(Range.linear(1, 50), State.empty, commands(bq), () => cleanup(bq))
  }

  def cleanup(bq: BigQuery): Unit = {
    // Clean up any remaining datasets
    bq.listDatasets()
      .iterateAll()
      .forEach(dataset => bq.delete(dataset.getDatasetId(), BigQuery.DatasetDeleteOption.deleteContents()))
  }

  def commandCreateDataset(bq: BigQuery): CommandIO[State] =
    new Command[State, CreateDataset, Unit] {

      override def gen(s: State): Option[Gen[CreateDataset]] =
        Some(for {
          datasetId <- genDatasetId
          location <- genLocation
          description <- genDescription
        } yield CreateDataset(datasetId, location, description))

      override def execute(env: Environment, i: CreateDataset): Either[String, Unit] = {
        try {
          val datasetInfo = DatasetInfo
            .newBuilder(i.datasetId)
            // .setLocation(i.location)
            .setDescription(i.description)
            .build()
          Right(bq.create(datasetInfo))
        } catch {
          case e: BigQueryException => Left(e.getMessage)
        }
      }

      override def update(s: State, i: CreateDataset, o: Var[Unit]): State =
        s.copy(datasets = s.datasets + i.datasetId)

      override def ensure(env: Environment, s0: State, s: State, i: CreateDataset, o: Unit): Result =
        Result.all(List(s.datasets.contains(i.datasetId) ==== true))
    }

  def commandGetDataset(bq: BigQuery): CommandIO[State] =
    new Command[State, GetDataset, Option[Dataset]] {

      override def gen(s: State): Option[Gen[GetDataset]] =
        Some(s.datasets.toList match {
          case Nil => genDatasetId.map(GetDataset.apply)
          case existing =>
            Gen.frequency1(
              80 -> Gen.elementUnsafe(existing).map(GetDataset.apply),
              20 -> genDatasetId.map(GetDataset.apply)
            )
        })

      override def execute(env: Environment, i: GetDataset): Either[String, Option[Dataset]] = {
        try {
          Right(Option(bq.getDataset(i.datasetId)))
        } catch {
          case _: BigQueryException => Right(None)
        }
      }

      override def update(s: State, i: GetDataset, o: Var[Option[Dataset]]): State = s

      override def ensure(
        env: Environment,
        s0: State,
        s: State,
        apiRequest: GetDataset,
        apiResult: Option[Dataset]
      ): Result =
        (s.datasets.contains(apiRequest.datasetId), apiResult) match {
          case (false, None) => Result.success
          case (true, Some(actual)) =>
            Result.all(List(actual.getDatasetId.getDataset ==== apiRequest.datasetId))
          case (false, Some(actual)) =>
            Result.failure.log(s"Expected dataset $apiRequest.datasetId not to be present in state $s but it was")
          case _ =>
            Result.failure.log(s"Expected dataset $apiRequest.datasetId to be present in state $s but it was not")
        }
    }

  def commandListDatasets(bq: BigQuery): CommandIO[State] =
    new Command[State, ListDatasets, List[Dataset]] {

      override def update(s0: State, i: ListDatasets, o: Var[List[Dataset]]): State = s0

      override def ensure(env: Environment, before: State, after: State, i: ListDatasets, o: List[Dataset]): Result =
        Result.all(List(o.map(_.getDatasetId.getDataset).toSet ==== (before.datasets ++ Set("main"))))

      override def gen(s: State): Option[Gen[ListDatasets]] =
        Some(Gen.constant(ListDatasets()))

      override def execute(env: Environment, i: ListDatasets): Either[String, List[Dataset]] = {
        Right(bq.listDatasets().iterateAll().asScala.toList)
      }
    }

  def commandDeleteDataset(bq: BigQuery): CommandIO[State] =
    new Command[State, DeleteDataset, Boolean] {

      override def gen(s: State): Option[Gen[DeleteDataset]] =
        if (s.datasets.isEmpty) None
        else Some(Gen.elementUnsafe((s.datasets - "main").toList).map(DeleteDataset.apply))

      override def execute(env: Environment, i: DeleteDataset): Either[String, Boolean] = {
        try {
          Right(bq.delete(i.datasetId))
        } catch {
          case e: BigQueryException => Left(e.getMessage)
        }
      }

      override def update(s: State, i: DeleteDataset, o: Var[Boolean]): State =
        s.copy(datasets = s.datasets - i.datasetId)

      override def ensure(env: Environment, s0: State, s: State, i: DeleteDataset, o: Boolean): Result =
        Result.all(List(o ==== true, s.datasets.contains(i.datasetId) ==== false))
    }

  def commandCreateTable(bq: BigQuery): CommandIO[State] =
    new Command[State, CreateTable, Table] {
      override def gen(s: State): Option[Gen[CreateTable]] =
        if (s.datasets.isEmpty) None
        else
          Some(for {
            datasetId <- Gen.elementUnsafe(s.datasets.toList)
            tableId <- genTableId
            schema <- genSchema
          } yield CreateTable(datasetId, tableId, schema))

      override def execute(env: Environment, i: CreateTable): Either[String, Table] = {
        try {

          val tableDefinition = StandardTableDefinition.of(i.schema)
          val tableId = TableId.of(i.datasetId, i.tableId)
          val tableInfo = TableInfo.newBuilder(tableId, tableDefinition).build()
          Right(bq.create(tableInfo))
        } catch {
          case e: BigQueryException => Left(e.getMessage)
        }
      }

      override def update(s: State, i: CreateTable, o: Var[Table]): State =
        s.copy(
          datasets = s.datasets + i.datasetId,
          tables = s.tables + (i.datasetId -> (s.tables.getOrElse(i.datasetId, Set.empty) + i.tableId))
        )

      override def ensure(env: Environment, s0: State, s: State, i: CreateTable, o: Table): Result =
        Result.all(
          List(s.tables.get(i.datasetId).exists(_.contains(i.tableId)) ==== true, o.getTableId.getTable ==== i.tableId)
        )
    }

  def commandGetTable(bq: BigQuery): CommandIO[State] =
    new Command[State, GetTable, Option[Table]] {
      override def gen(s: State): Option[Gen[GetTable]] =
        if (s.tables.isEmpty) None
        else
          Some(for {
            datasetId <- Gen.elementUnsafe(s.tables.keys.toList)
            tableId <- Gen.elementUnsafe(s.tables(datasetId).toList)
          } yield GetTable(datasetId, tableId))

      override def execute(env: Environment, i: GetTable): Either[String, Option[Table]] = {
        try {
          Right(Option(bq.getTable(i.datasetId, i.tableId)))
        } catch {
          case _: BigQueryException => Right(None)
        }
      }

      override def update(s: State, i: GetTable, o: Var[Option[Table]]): State = s

      override def ensure(
        env: Environment,
        s0: State,
        s: State,
        apiRequest: GetTable,
        apiResult: Option[Table]
      ): Result =
        (s.tables.get(apiRequest.datasetId).exists(_.contains(apiRequest.tableId)), apiResult) match {
          case (false, None) => Result.success
          case (true, Some(actual)) =>
            Result.all(List(actual.getTableId.getTable ==== apiRequest.tableId))
          case (false, Some(actual)) =>
            Result.failure.log(
              s"Expected table $apiRequest.datasetId.$apiRequest.tableId not to be present in state $s but it was"
            )
          case _ =>
            Result.failure.log(
              s"Expected table $apiRequest.datasetId.$apiRequest.tableId to be present in state $s but it was not"
            )
        }
    }

  def commandDeleteTable(bq: BigQuery): CommandIO[State] =
    new Command[State, DeleteTable, Boolean] {
      override def gen(s: State): Option[Gen[DeleteTable]] =
        if (s.tables.isEmpty) None
        else
          Some(for {
            datasetId <- Gen.element("nonexisting_dataset_id", s.tables.keys.toList)
            tableId <- Gen.element("nonexisting_table_id", s.tables.get(datasetId).toList.flatMap(_.toList))
          } yield DeleteTable(datasetId, tableId))

      override def execute(env: Environment, i: DeleteTable): Either[String, Boolean] = {
        try {
          Right(bq.delete(i.datasetId, i.tableId))
        } catch {
          case e: BigQueryException => Left(e.getMessage)
        }
      }

      override def update(s: State, i: DeleteTable, o: Var[Boolean]): State =
        s.copy(tables =
          s.tables - i.datasetId + (i.datasetId -> (s.tables.getOrElse(i.datasetId, Set.empty) - i.tableId))
        )

      override def ensure(env: Environment, s0: State, s: State, i: DeleteTable, o: Boolean): Result =
        Result.all(List(o ==== true, !s.tables.get(i.datasetId).exists(_.contains(i.tableId)) ==== true))
    }

  def commandListTables(bq: BigQuery): CommandIO[State] =
    new Command[State, ListTables, List[Table]] {
      override def gen(s: State): Option[Gen[ListTables]] =
        if (s.tables.isEmpty) None
        else
          Some(for {
            datasetId <- Gen.elementUnsafe(s.tables.keys.toList)
          } yield ListTables(datasetId))

      override def execute(env: Environment, i: ListTables): Either[String, List[Table]] = {
        Right(bq.listTables(i.datasetId).iterateAll().asScala.toList)
      }

      override def update(s0: State, i: ListTables, o: Var[List[Table]]): State = s0

      override def ensure(env: Environment, before: State, after: State, i: ListTables, o: List[Table]): Result =
        Result.all(
          List(o.map(_.getTableId.getTable).toSet ==== (before.tables.getOrElse(i.datasetId, Set.empty) ++ Set("main")))
        )
    }
}
