package muse.service

import muse.domain.session.UserSession
import muse.domain.spotify.AuthCodeFlowData
import muse.service.spotify.{SpotifyAuthService, SpotifyService}
import muse.utils.Utils
import sttp.client3.SttpBackend
import zio.*
import zio.json.*
import zio.stream.{ZPipeline, ZStream}

import java.io.IOException
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit

// TODO: make generic?
trait UserSessions {
  def addUserSession(userId: String, authData: AuthCodeFlowData): UIO[String]
  def getUserSession(sessionId: String): UIO[Option[UserSession]]
  def updateAndGetSessions(sessionId: String): UIO[Option[(UserSession, SpotifyService)]]
  def deleteUserSession(sessionId: String): UIO[Unit]
  def deleteUserSessionByUserId(userId: String): UIO[Unit]
  def updateUserSession(sessionId: String)(f: UserSession => UserSession): UIO[Option[UserSession]]

  /**
   * Persistence.
   */
  def loadSessions: ZIO[Scope, Throwable, List[UserSession]]
  def saveSessions: ZIO[Scope, IOException, Unit]

  final def getAccessToken(sessionId: String)  = getUserSession(sessionId).map(_.map(_.accessToken))
  final def getUserId(sessionId: String)       = getUserSession(sessionId).map(_.map(_.id))
  final def getRefreshToken(sessionId: String) = getUserSession(sessionId).map(_.map(_.refreshToken))
}

object UserSessions {
  val layer = ZLayer.scoped {
    for {
      sttp <- ZIO.service[SttpBackend[Task, Any]]
      auth <- ZIO.service[SpotifyAuthService]
      res  <- ZIO.acquireRelease(
                Ref
                  .Synchronized
                  .make(Map.empty)
                  .map(UserSessionsLive.apply(_, auth, sttp))
                  // Load sessions from file. When scope closes, save the sessions to file.
                  .tap(_.loadSessions))(cleanup)
    } yield res
  }

  def cleanup(sessions: UserSessions) =
    sessions
      .saveSessions
      .catchAll(e => ZIO.logErrorCause(s"Failed to save sessions ${e.toString}", Cause.fail(e)))

  def addUserSession(userId: String, authData: AuthCodeFlowData) =
    ZIO.serviceWithZIO[UserSessions](_.addUserSession(userId, authData))

  def updateAndGetSessions(sessionId: String) =
    ZIO.serviceWithZIO[UserSessions](_.updateAndGetSessions(sessionId))

  def getUserSession(sessionId: String) = ZIO.serviceWithZIO[UserSessions](_.getUserSession(sessionId))

  def updateUserSession(sessionId: String)(f: UserSession => UserSession) =
    ZIO.serviceWithZIO[UserSessions](_.updateUserSession(sessionId)(f))

  def deleteUserSession(sessionId: String) = ZIO.serviceWithZIO[UserSessions](_.deleteUserSession(sessionId))

  def deleteUserSessionByUserId(userId: String) =
    ZIO.serviceWithZIO[UserSessions](_.deleteUserSessionByUserId(userId))
}

final case class UserSessionsLive(
    sessionsR: Ref.Synchronized[Map[String, UserSession]],
    spotifyAuth: SpotifyAuthService,
    sttp: SttpBackend[Task, Any])
    extends UserSessions {
  val layer = ZLayer.succeed(sttp) ++ ZLayer.succeed(spotifyAuth)

  override def addUserSession(userId: String, authData: AuthCodeFlowData) = for {
    expiration <- Utils.getExpirationInstant(authData.expiresIn)
    guid       <- Random.nextUUID
    newSession  = guid.toString
    session     = UserSession(newSession, userId, expiration, authData.accessToken, authData.refreshToken)
    _          <- sessionsR.update(_ + (newSession -> session))
  } yield newSession

  override def getUserSession(sessionId: String) = sessionsR.get.map { sessions => sessions.get(sessionId) }

  override def deleteUserSession(sessionId: String) = sessionsR.update(_.removed(sessionId))

  override def deleteUserSessionByUserId(userId: String) =
    sessionsR.update(_.filterNot(_._2.id == userId))

  override def updateUserSession(sessionId: String)(f: UserSession => UserSession) =
    sessionsR
      .updateAndGet { sessions => sessions.get(sessionId).fold(sessions) { current => sessions.updated(sessionId, f(current)) } }
      .map(_.get(sessionId))

  // TODO: use config.
  val SESSIONS_FILE = "src/main/resources/UserSessions.json"

  def loadSessions: ZIO[Scope, Throwable, List[UserSession]] = for {
    partitioned                 <- Utils
                                     .readFile(SESSIONS_FILE)
                                     .via(ZPipeline.splitLines)
                                     .map(_.fromJson[UserSession])
                                     .partitionEither(ZIO.succeed(_))
    // Deconstruct tuple.
    (errorStream, sessionStream) = partitioned

    errorCount <- errorStream.runCount
    _          <- ZIO.unless(errorCount == 0)(ZIO.logError(s"Could not load $errorCount sessions"))
    result     <- sessionsR.updateAndGetZIO { sessionsInMem =>
                    sessionStream.runFold(sessionsInMem)((sessions, s: UserSession) =>
                      sessions
                        .get(s.sessionId)
                        // If is already a session loaded in memory for a given session cookie
                        // ignore the session from file.
                        .fold(sessions + (s.sessionId -> s))(_ => sessions))
                  }
    _          <- ZIO.logInfo(s"Successfully loaded ${result.size} session(s).")
  } yield result.values.toList

  override def saveSessions: ZIO[Scope, IOException, Unit] = for {
    sessions <- sessionsR.get.map(_.values.toList)
    stream    = ZStream
                  .fromIterable(sessions)
                  .map(_.toJson)
                  .map(s => s + '\n')
    count    <- stream.runCount
    _        <- Utils
                  .writeToFile(SESSIONS_FILE, stream)
                  .fold(
                    e => ZIO.logErrorCause(s"Failed to save sessions $e", Cause.fail(e)),
                    _ => ZIO.logInfo(s"Successfully saved $count sessions."))
  } yield ()

  override def updateAndGetSessions(sessionId: String) = (for {
    maybeUser      <- getUserSession(sessionId)
    session        <- ZIO.fromOption(maybeUser).orElseFail(new Exception(s"Session $sessionId not found."))
    updatedSession <-
      if (session.expiration.isAfter(Instant.now()))
        ZIO.succeed(session)
      else
        for {
          authData      <- SpotifyAuthService
                             // TODO: Add retry for this.
                             .requestNewAccessToken(session.refreshToken)
          newExpiration <- Utils.getExpirationInstant(authData.expiresIn)
          newSession    <- updateUserSession(session.sessionId) {
                             _.copy(accessToken = authData.accessToken, expiration = newExpiration)
                           }
          // These 'get' calls should be a-ok.
          _             <- ZIO.logInfo(s"Session Updated ${newSession.get.conciseString}")
        } yield newSession.get
    spotify        <- SpotifyService.live(updatedSession.accessToken)
  } yield session -> spotify).option.provideLayer(layer)
}
