package muse.server

import caliban.Value.StringValue
import caliban.interop.tapir.{StreamTransformer, WebSocketHooks}
import caliban.*
import io.netty.handler.codec.http.HttpHeaderNames
import muse.domain.common.Types.SessionId
import muse.domain.error.{MuseError, RateLimited, Unauthorized}
import muse.domain.session.UserSession
import muse.server.graphql.MuseGraphQL
import muse.service.spotify.{SpotifyAPI, SpotifyAuthService, SpotifyService}
import muse.service.{RedisService, RequestSession, UserSessions}
import muse.utils.Utils
import nl.vroste.rezilience.Bulkhead
import nl.vroste.rezilience.Bulkhead.{BulkheadError, BulkheadException, WrappedError}
import sttp.client3.SttpBackend
import zio.*
import zio.http.Http.Route
import zio.http.middleware.*
import zio.http.model.HttpError
import zio.http.*
import zio.redis.Redis
import zio.stream.ZStream

import java.time.Instant

object MuseMiddleware {

  type SessionEnv = RedisService & UserSessions & RequestSession[UserSession] & RequestSession[SpotifyService] &
    SttpBackend[Task, Any] & Ref[Option[Long]]

  final def InjectSessionAndRateLimit: RequestHandlerMiddleware.Simple[SessionEnv, Throwable] =
    new RequestHandlerMiddleware.Simple[SessionEnv, Throwable] {
      override def apply[Env <: SessionEnv, Err >: Throwable](handler: Handler[Env, Err, Request, Response])(
          implicit trace: Trace): Handler[Env, Err, Request, Response] =
        Handler.fromFunctionZIO[Request] { request =>
          // Init Session.
          for {
            sessionData        <- {
              extractRequestAuth(request) match
                case None            => ZIO.fail(Unauthorized("Missing Auth Header"))
                case Some(sessionId) => UserSessions.getSessionAndBulkhead(SessionId(sessionId))
            }
            (session, bulkhead) = sessionData
            _                  <- RequestSession.set[UserSession](Some(session))
            spotify            <- SpotifyService.live(session.accessToken)
            _                  <- RequestSession.set[SpotifyService](Some(spotify))
            // Run handler with bulkhead.
            result             <- bulkhead {
                                    handler.runZIO(request)
                                  }.mapError {
                                    case WrappedError(error)        => error
                                    case Bulkhead.BulkheadRejection => RateLimited
                                  }
          } yield result
        }
    }

  private def extractRequestAuth(request: Request) = request
    .cookieValue(COOKIE_KEY)
    .orElse(request.authorization)
    .map(_.toString)

  /**
   * Logs the requests made to the server.
   *
   * It also adds a request ID to the logging context, so any further logging that occurs in the handler can be associated with
   * the same request.
   */

  /**
   * Add log status, method, url and time taken from req to res
   */
  final def debug: RequestHandlerMiddleware.Simple[Any, Nothing] =
    new RequestHandlerMiddleware.Simple[Any, Nothing] {
      override def apply[R1 <: Any, Err1 >: Nothing](
          handler: Handler[R1, Err1, Request, Response]
      )(implicit trace: Trace): Handler[R1, Err1, Request, Response] =
        Handler.fromFunctionZIO { request =>
          for {
            traceId             <- Random.nextUUID
            withTime            <- ZIO.logAnnotate("trace-id", traceId.toString) {
                                     handler.runZIO(request).timed
                                   }
            (duration, response) = withTime
            _                   <- ZIO.logInfo(s"${response.status.code} ${request.method} ${request.url.encode} ${duration.toMillis}ms")
          } yield response
        }
    }

  object Websockets {

    private val makeSessionRef = Ref.make[Option[UserSession]](None)

    def live[R](interpreter: GraphQLInterpreter[R, CalibanError]) =
      Http.fromHttpZIO[Request] { request =>
        val maybeAuth = extractRequestAuth(request)
        MuseMiddleware.Websockets.configure(interpreter, maybeAuth)
      }

    def configure[R](interpreter: GraphQLInterpreter[R, CalibanError], maybeUserSessionId: Option[String]) = for {
      ref        <- makeSessionRef
      authSession = createSession[UserSession](ref)

      // Authorize with cookie! Ignored because we can also authorize with payload.
      _ <- initSession(authSession, maybeUserSessionId).ignore
    } yield {
      // Authorize with input value!
      val connectionInit = WebSocketHooks.init { payload =>
        val maybeSessionId = payload match
          case InputValue.ObjectValue(fields) =>
            fields.get("Authorization").flatMap {
              case StringValue(s) => Some(s)
              case _              => None
            }
          case _                              => None
        initSession(authSession, maybeSessionId)
      }

      // Ensure that each message has latest sessions in environment.
      val transformService = WebSocketHooks.message(new StreamTransformer[SessionEnv, Throwable] {
        def transform[R1 <: SessionEnv, E1 >: Throwable](
            stream: ZStream[R1, E1, GraphQLWSOutput]
        ): ZStream[R1, E1, GraphQLWSOutput] =
          ZStream.fromZIO(
            for {
              sessionId     <- authSession.get.map(_.sessionId)
              latestSession <- UserSessions.getUserSession(sessionId)
              spotify       <- SpotifyService.live(latestSession.accessToken)
              _             <- authSession.set(Some(latestSession)) <&>
                                 RequestSession.set[UserSession](Some(latestSession)) <&>
                                 RequestSession.set[SpotifyService](Some(spotify))
            } yield ()
          ) *> stream
      })

      import sttp.tapir.json.zio.*

      ZHttpAdapter.makeWebSocketService(
        interpreter,
        webSocketHooks = connectionInit ++ transformService
      )
    }

    // If the session is already set, do nothing. Otherwise, attempt to initialize UserSession.
    private def initSession(ref: RequestSession[UserSession], maybeUserSessionId: Option[String]) = ref.get.isSuccess.flatMap {
      case true  => ZIO.unit
      case false =>
        ZIO
          .fromOption(maybeUserSessionId).orElseFail(Unauthorized("Missing Auth: Unable to decode payload"))
          .flatMap(auth => UserSessions.getUserSession(SessionId(auth)))
          .flatMap(session => ref.set(Some(session)))
    }

    private def createSession[R: Tag](ref: Ref[Option[R]]) = {
      new RequestSession[R] {
        def get: IO[Unauthorized, R] =
          ref.get.flatMap {
            case Some(v) => ZIO.succeed(v)
            case None    => ZIO.fail(Unauthorized("Missing Websocket Auth"))
          }

        def set(session: Option[R]): UIO[Unit] = ref.set(session)
      }
    }
  }

}
