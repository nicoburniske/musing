package muse.server.graphql.resolver

import muse.domain.session.UserSession
import muse.server.graphql.subgraph.Review
import muse.service.RequestSession
import muse.service.persist.DatabaseService
import muse.utils.Utils
import zio.ZIO
import zio.query.{DataSource, Request, ZQuery}

import java.sql.SQLException
import java.time.temporal.ChronoUnit
import java.util.UUID

case class GetReview(reviewId: UUID) extends Request[SQLException, Option[Review]]

object GetReview {
  type Env = DatabaseService & RequestSession[UserSession]
  val MAX_REVIEWS_PER_REQUEST = 100

  def query(reviewId: UUID)             = ZQuery.fromRequest(GetReview(reviewId))(ReviewDataSource)
  def multiQuery(reviewIds: List[UUID]) = ZQuery.foreachPar(reviewIds)(query).map(_.flatten)

  def metric = Utils.timer("GetReview", ChronoUnit.MILLIS)

  val ReviewDataSource: DataSource[Env, GetReview] =
    DataSource.Batched.make("ReviewDataSource") { reqs =>
      DatasourceUtils.createBatchedDataSource(
        reqs,
        MAX_REVIEWS_PER_REQUEST,
        req =>
          for {
            session <- RequestSession.get[UserSession]
            review  <- DatabaseService
                         .getReviewWithPermissions(req.reviewId, session.userId)
          } yield review,
        // We are wrapping in 'Some' because single request returns 'Option' and batched request returns 'List'
        reqs =>
          for {
            session <- RequestSession.get[UserSession]
            reviews <-
              DatabaseService
                .getReviewsWithPermissions(reqs.map(_.reviewId).toList, session.userId)
          } yield reviews.map { Some(_) }.toVector,
        maybeReview => maybeReview.map(Review.fromTable),
        _.reviewId.toString,
        _.map(_.id.toString).getOrElse("")
      ) @@ metric.trackDuration
    }

}
