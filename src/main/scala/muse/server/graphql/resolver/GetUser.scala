package muse.server.graphql.resolver

import muse.domain.error.Unauthorized
import muse.domain.session.UserSession
import muse.server.graphql.subgraph.User
import muse.service.RequestSession
import muse.service.persist.DatabaseService
import zio.query.{DataSource, Request, ZQuery}

case class GetUser(id: String) extends Request[Nothing, User]

object GetUser {
  def query(maybeId: Option[String]) = maybeId match
    case Some(id) => queryByUserId(id)

  def queryByUserId(userId: String) = for {
    user <- ZQuery.fromZIO(RequestSession.get[UserSession])
    which = if (user.userId == userId) All else WithAccess
  } yield User(userId, GetUserReviews.query(userId, which), GetSpotifyProfile.query(userId))

  def currentUser: ZQuery[RequestSession[UserSession] & DatabaseService, Unauthorized, User] = for {
    userId <- ZQuery.fromZIO(RequestSession.get[UserSession]).map(_.userId)
  } yield User(userId, GetUserReviews.query(userId, All), GetSpotifyProfile.query(userId))
}
