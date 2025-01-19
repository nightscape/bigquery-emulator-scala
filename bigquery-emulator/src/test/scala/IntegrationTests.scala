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

  run { defer {
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
  }}
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

  case class State(datasets: Set[String])

  object State {
    def empty: State = State(Set.empty)
  }

  // Commands
  case class CreateDataset(datasetId: String, location: Option[String], description: String)
  case class GetDataset(datasetId: String)
  case class ListDatasets()
  case class DeleteDataset(datasetId: String)

  def genDatasetId: Gen[String] = for {
    c <- Gen.lower
    s <- Gen.string(Gen.alphaNum, Range.linear(1, 15))
  } yield c +: s

  def genLocation: Gen[Option[String]] =
    Gen.element(None, List())//"US", List("EU", "ASIA"))

  def genDescription: Gen[String] =
    Gen.string(Gen.unicode, Range.linear(1, 100))

  def commands(bq: BigQuery): List[CommandIO[State]] =
    List(commandCreateDataset(bq), /*commandGetDataset(bq),*/ commandDeleteDataset(bq), commandListDatasets(bq))

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
            //.setLocation(i.location)
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
            Gen.frequency1(80 -> Gen.elementUnsafe(existing).map(GetDataset.apply), 20 -> genDatasetId.map(GetDataset.apply))
        })

      override def execute(env: Environment, i: GetDataset): Either[String, Option[Dataset]] = {
        try {
          Right(Option(bq.getDataset(i.datasetId)))
        } catch {
          case _: BigQueryException => Right(None)
        }
      }

      override def update(s: State, i: GetDataset, o: Var[Option[Dataset]]): State = s

      override def ensure(env: Environment, s0: State, s: State, apiRequest: GetDataset, apiResult: Option[Dataset]): Result =
        (s.datasets.contains(apiRequest.datasetId), apiResult) match {
          case (false, None) => Result.success
          case (true, Some(actual)) =>
            Result.all(List(actual.getDatasetId.getDataset ==== apiRequest.datasetId))
          case (false, Some(actual)) =>
            Result.failure.log(s"Expected dataset $apiRequest.datasetId not to be present in state $s but it was")
          case _ => Result.failure.log(s"Expected dataset $apiRequest.datasetId to be present in state $s but it was not")
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
        else Some(Gen.elementUnsafe(s.datasets.toList).map(DeleteDataset.apply))

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

}
