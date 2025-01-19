package dev.mauch.bqemulator

import kyo.*
import java.util.concurrent.Executors
import sttp.tapir.*
import sttp.tapir.server.netty.sync.NettySyncServer
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.server.netty.{NettyFutureServerInterpreter, FutureRoute}
import sttp.tapir.files.*

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global


import sttp.shared.Identity
import sttp.tapir.server.netty.NettyFutureServer
import sttp.tapir.server.netty.NettyConfig
import dev.mauch.bqemulator.BigQueryEndpoints.*
import dev.mauch.zetasql.DuckDB
import sttp.tapir.server.netty.NettyKyoServerOptions
import sttp.tapir.server.netty.NettyKyoServer
import sttp.tapir.server.interceptor.RequestInterceptor
import sttp.tapir.server.interceptor.RequestInterceptor.RequestResultTransform
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interceptor.RequestResult
import io.netty.channel.ChannelHandlerContext
import sttp.tapir.server.netty.NettyResponseContent
import sttp.tapir.server.netty.NettyServerRequest
import io.netty.handler.codec.http.HttpMessage
import org.reactivestreams.Subscriber
import java.util.concurrent.Flow.Subscription
import io.netty.handler.codec.http.HttpContent
import java.util.concurrent.CountDownLatch
import io.netty.util.CharsetUtil
import scala.collection.mutable.ArrayBuffer
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.buffer.ByteBufInputStream
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelHandler
import io.netty.handler.codec.http.HttpServerCodec
import org.playframework.netty.http.HttpStreamsServerHandler
import io.netty.handler.codec.http.HttpContentDecompressor
import io.netty.handler.logging.LoggingHandler
// first interpret as swagger ui endpoints, backend by the appropriate yaml

object Server extends KyoApp:
  println("Starting")
  import scala.concurrent._
  import scala.concurrent.duration._
  import scala.util._


  val swaggerEndpoints = SwaggerInterpreter().fromEndpoints[Future](BigQueryEndpoints.generatedEndpoints, "BigQuery Emulator", "0.0.1")
  println("Got swagger endpoints")

  val serverEndpoints = swaggerEndpoints ++ List(
    staticResourceGetServerEndpoint("$discovery" / "rest")(this.getClass.getClassLoader, "discovery.json"),
    createTable.serverLogic[Future](_ => Future.successful(Right(Table())))
  )

  //println(serverEndpoints.map(_.show).mkString("\n"))

  val clock =
    import AllowUnsafe.embrace.danger
    Clock(Clock.Unsafe(Executors.newSingleThreadScheduledExecutor()))

  val options =
      NettyKyoServerOptions
          .default(enableLogging = true)
          .forkExecution(false)
          .prependInterceptor(RequestInterceptor.transformServerRequest(request => {
            //println(s"Request: $request")
            request
          }))
          .appendInterceptor(RequestInterceptor.transformResult[kyo.internal.KyoSttpMonad.M](new RequestResultTransform[kyo.internal.KyoSttpMonad.M] {
            def apply[A](request: ServerRequest, result: RequestResult[A]): kyo.internal.KyoSttpMonad.M[RequestResult[A]] = {
              //println(s"Result: $result")
              result match {
                case RequestResult.Failure(e) =>
                  println(s"Failure: $e")
                case RequestResult.Response(e) => ()
              }
              result
            }
          }))
  final val ServerCodecHandlerName = "serverCodecHandler"
  final val WebSocketControlFrameHandlerName = "wsControlFrameHandler"
  def decompressingInitPipeline(cfg: NettyConfig)(pipeline: ChannelPipeline, handler: ChannelHandler): Unit = {
    cfg.sslContext.foreach(s => pipeline.addLast(s.newHandler(pipeline.channel().alloc())))
    pipeline.addLast(ServerCodecHandlerName, new HttpServerCodec())
    pipeline.addLast("decompressor", new HttpContentDecompressor())
    pipeline.addLast(new HttpStreamsServerHandler())
    pipeline.addLast(handler)
    if (cfg.addLoggingHandler) pipeline.addLast(new LoggingHandler())
    ()
  }
  val cfg =
      NettyConfig.default
          .initPipeline(decompressingInitPipeline)
          .withSocketKeepAlive
          .copy(lingerTimeout = Some(1.seconds))
          .withGracefulShutdownTimeout(1.seconds)
          .withAddLoggingHandler

  val server =
      NettyKyoServer(options, cfg)
          .host("0.0.0.0")

  run {
    defer {
      val port: Int = System.property[Int]("PORT", 9050).now
      println(s"Starting server on port $port")
      val duckdb = DuckDB.init.now
      val handler = Env.run(duckdb)(Handler.init).now
      val handledEndpoints = Env.run(handler)(KyoEndpoints.init).later
      val nettyBinding = Routes.run(server.port(port))(handledEndpoints).now
      println("Server started")
      Async.sleep(1000.seconds).now
      nettyBinding.stop().now
      println("Server stopped")
    }
  }
