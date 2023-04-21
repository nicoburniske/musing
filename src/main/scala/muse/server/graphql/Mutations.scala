package muse.server.graphql

import caliban.schema.{ArgBuilder, Schema}
import muse.domain.common.EntityType
import muse.domain.common.Types.UserId
import muse.domain.error.{BadRequest, Forbidden, InvalidEntity, InvalidUser, MuseError, Unauthorized}
import muse.domain.event.ReviewUpdate.{CreatedComment, DeletedComment, UpdatedComment}
import muse.domain.event.ReviewUpdate
import muse.domain.mutate.*
import muse.domain.session.UserSession
import muse.domain.spotify.{
  ErrorReason,
  ErrorResponse,
  StartPlaybackBody,
  TransferPlaybackBody,
  UriOffset,
  PositionOffset as SpotifyPostionOffset
}
import muse.domain.{spotify, table}
import muse.server.graphql.subgraph.{Comment, Review}
import muse.service.RequestSession
import muse.service.persist.DatabaseService
import muse.service.spotify.{SpotifyError, SpotifyService}
import muse.utils.Utils.*
import sttp.model.{Method, Uri}
import zio.{Hub, IO, Task, UIO, ZIO}

import java.sql.SQLException
import java.time.temporal.ChronoUnit
import java.util.UUID

// TODO: add checking for what constitutes a valid comment. What does rating represent?
// TODO: Consider adding ZQuery for batched operations to DB.
type MutationEnv   = RequestSession[UserSession] & DatabaseService & RequestSession[SpotifyService] & Hub[ReviewUpdate]
type MutationError = Throwable | MuseError
type Mutation[A]   = ZIO[MutationEnv, MutationError, A]

final case class Mutations(
    createReview: CreateReviewInput => ZIO[MutationEnv, MutationError, Review],
    createComment: CreateCommentInput => ZIO[MutationEnv, MutationError, Comment],
    linkReviews: LinkReviewsInput => ZIO[MutationEnv, MutationError, Boolean],
    updateReviewLink: UpdateReviewLinkInput => ZIO[MutationEnv, MutationError, Boolean],
    updateReview: UpdateReviewInput => ZIO[MutationEnv, MutationError, Review],
    updateReviewEntity: UpdateReviewEntityInput => ZIO[MutationEnv, MutationError, Review],
    updateComment: UpdateCommentInput => ZIO[MutationEnv, MutationError, Comment],
    updateCommentIndex: UpdateCommentIndexInput => ZIO[MutationEnv, MutationError, Boolean],
    deleteReview: DeleteReviewInput => ZIO[MutationEnv, MutationError, Boolean],
    deleteComment: DeleteCommentInput => ZIO[MutationEnv, MutationError, Boolean],
    deleteReviewLink: DeleteReviewLinkInput => ZIO[MutationEnv, MutationError, Boolean],
    shareReview: ShareReviewInput => ZIO[MutationEnv, MutationError, Boolean]
)

object Mutations {

  val live = Mutations(
    i => createReview(i.input),
    i => createComment(i.input),
    i => linkReviews(i.input),
    i => updateReviewLink(i.input),
    i => updateReview(i.input),
    i => updateReviewEntity(i.input),
    i => updateComment(i.input),
    i => updateCommentIndex(i.input),
    i => deleteReview(i.input),
    i => deleteComment(i.input),
    i => deleteReviewLink(i.input),
    i => shareReview(i.input)
  )

  // TODO: Wrap this in a single transaction.
  def createReview(create: CreateReview) = for {
    user       <- RequestSession.get[UserSession].map(_.userId)
    r          <- DatabaseService.createReview(user, create)
    _          <- ZIO.logInfo(s"Successfully created review! ${r.reviewName}")
    maybeEntity = create.entity.map(e => table.ReviewEntity(r.reviewId, e.entityType, e.entityId))
    _          <- ZIO.fromOption(maybeEntity).flatMap(updateReviewEntityFromTable).orElse(ZIO.logInfo("No entity included!"))
    _          <- ZIO
                    .fromOption(create.link).flatMap { l =>
                      DatabaseService.linkReviews(LinkReviews(l.parentReviewId, r.reviewId, None)) *>
                        ZIO.logInfo(s"Successfully linked review ${r.reviewId} to ${l.parentReviewId}")
                    }.orElse(ZIO.logInfo("No link included!"))
  } yield Review.fromTable(r, maybeEntity)

  private def updateReviewEntityFromTable(r: table.ReviewEntity) =
    DatabaseService
      .updateReviewEntity(r).fold(
        sqlException => ZIO.logError(s"Failed to update review entity $r! ${sqlException.getMessage}"),
        _ => ZIO.logInfo(s"Successfully updated review entity! ${r.reviewId}")
      ).unit

  val createCommentMetric                  = timer("createComment", ChronoUnit.MILLIS)
  def createComment(create: CreateComment) = (for {
    user      <- RequestSession.get[UserSession]
    _         <- ZIO
                   .fail(BadRequest(Some("Comment must have a non-empty body")))
                   .when(create.comment.isEmpty)
    _         <- ZIO.foreachPar(create.entities)(e => validateEntity(e._2, e._1))
                   <&> validateCommentPermissions(user.userId, create.reviewId)
    result    <- DatabaseService.createReviewComment(user.userId, create).map {
                   case (comment, index, parentChild, entities) => (comment, index, parentChild.fold(Nil)(List(_)), entities)
                 }
    comment    = Comment.fromTable.tupled(result)
    published <- ZIO.serviceWithZIO[Hub[ReviewUpdate]](_.publish(CreatedComment(comment)))
    _         <- ZIO.logError("Failed to publish comment creation").unless(published)
  } yield comment) @@ createCommentMetric.trackDuration

  def linkReviews(link: LinkReviews) = for {
    user   <- RequestSession.get[UserSession]
    _      <- ZIO.fail(BadRequest(Some("Can't link a review to itself"))).when(link.parentReviewId == link.childReviewId)
    _      <- validateReviewPermissions(user.userId, link.parentReviewId)
    result <- DatabaseService.linkReviews(link)
  } yield result

  def updateReviewLink(link: UpdateReviewLink) = for {
    user   <- RequestSession.get[UserSession]
    _      <- ZIO.fail(BadRequest(Some("Can't link a review to itself"))).when(link.parentReviewId == link.childReviewId)
    _      <- ZIO.fail(BadRequest(Some("Can't have negative index"))).when(link.linkIndex < 0)
    _      <- validateReviewPermissions(user.userId, link.parentReviewId)
    result <- DatabaseService.updateReviewLink(link)
  } yield result

  def updateReview(update: UpdateReview) = for {
    user    <- RequestSession.get[UserSession]
    _       <- validateReviewPermissions(user.userId, update.reviewId)
    review  <- DatabaseService.updateReview(update)
    details <- DatabaseService.getReviewEntity(update.reviewId)
  } yield Review.fromTable(review, details)

  def updateReviewEntity(update: UpdateReviewEntity) = for {
    user        <- RequestSession.get[UserSession]
    _           <- validateReviewPermissions(user.userId, update.reviewId)
    asTable      = update.toTable
    maybeReview <- DatabaseService.getReview(update.reviewId) <&> updateReviewEntityFromTable(asTable)
    review      <- ZIO.fromOption(maybeReview).orElseFail(BadRequest(Some("Review not found.")))
  } yield Review.fromTable(review, Some(asTable))

  val updateCommentMetric                  = timer("UpdateComment", ChronoUnit.MILLIS)
  def updateComment(update: UpdateComment) = (for {
    user      <- RequestSession.get[UserSession]
    _         <- validateCommentEditingPermissions(user.userId, update.reviewId, update.commentId)
    _         <- DatabaseService.updateComment(update)
    comment   <- DatabaseService
                   .getComment(update.commentId, user.userId)
                   .someOrFail(BadRequest(Some("Comment does not exist")))
                   .map(Comment.fromTable.tupled)
    published <- ZIO.serviceWithZIO[Hub[ReviewUpdate]](_.publish(UpdatedComment(comment)))
    _         <- ZIO.logError("Failed to publish comment update").unless(published)
  } yield comment) @@ updateCommentMetric.trackDuration

  val updateCommentIndexMetric = timer("UpdateCommentIndex", ChronoUnit.MILLIS)

  def updateCommentIndex(update: UpdateCommentIndex) = (for {
    user <- RequestSession.get[UserSession]
    _    <- ZIO.fail(BadRequest(Some("Can't have negative index"))).when(update.index < 0)
    _    <- validateCommentEditingPermissions(user.userId, update.reviewId, update.commentId)

    // ID -> Index
    updatedCommentIndices: List[(Long, Int)] <- DatabaseService.updateCommentIndex(update.commentId, update.index)

    result <- updatedCommentIndices match
                case Nil     => ZIO.succeed(false)
                case updated =>
                  (for {
                    hub       <- ZIO.service[Hub[ReviewUpdate]]
                    comments  <- DatabaseService.getComments(updated.map(_._1), user.userId).map(Comment.fromTableRows)
                    published <- ZIO.foreachPar(comments)(c => hub.publish(UpdatedComment(c))).map(_.forall(identity))
                    _         <- ZIO.logError("Failed to publish comment update").unless(published)
                    _         <- ZIO.logInfo("Successfully published update messages")
                  } yield ()).forkDaemon.as(true)
  } yield result).addTimeLog("UpdateCommentIndex") @@ updateCommentIndexMetric.trackDuration

  def deleteComment(d: DeleteComment): Mutation[Boolean] = (for {
    user              <- RequestSession.get[UserSession]
    _                 <- validateCommentEditingPermissions(user.userId, d.reviewId, d.commentId)
    result            <- DatabaseService.deleteComment(d)
    (deleted, updated) = result
    // TODO: Is there a better way to do this?
    _                 <- publishDeletedComments(user.userId, d.reviewId, deleted, updated).forkDaemon
  } yield deleted.nonEmpty || updated.nonEmpty).addTimeLog("DeleteComment")

  def publishDeletedComments(userId: UserId, reviewId: UUID, deletedCommentIds: List[Long], updatedCommentIds: List[Long]) = for {
    updatedComments             <- DatabaseService.getComments(updatedCommentIds, userId)
    fromTable                    = Comment.fromTableRows(updatedComments)
    hub                         <- ZIO.service[Hub[ReviewUpdate]]
    updates                      = ZIO.foreachPar(fromTable)(c => hub.publish(UpdatedComment(c)))
    deletes                      = ZIO.foreachPar(deletedCommentIds)(id => hub.publish(DeletedComment(reviewId, id)))
    published                   <- updates <&> deletes
    (updateResult, deleteResult) = published
    allSuccess                   = updateResult.forall(identity) && deleteResult.forall(identity)
    _                           <- ZIO.logError("Failed to publish comment delete events").unless(allSuccess)
  } yield ()

  def deleteReview(d: DeleteReview): Mutation[Boolean] = for {
    user   <- RequestSession.get[UserSession]
    _      <- validateReviewPermissions(user.userId, d.id)
    result <- DatabaseService.deleteReview(d)
  } yield result

  def deleteReviewLink(link: DeleteReviewLink) = for {
    user   <- RequestSession.get[UserSession]
    _      <- validateReviewPermissions(user.userId, link.parentReviewId)
    result <- DatabaseService.deleteReviewLink(link)
  } yield result

  def shareReview(s: ShareReview): ZIO[MutationEnv, MutationError | InvalidUser, Boolean] = for {
    userId <- RequestSession.get[UserSession].map(_.userId)
    _      <- ZIO.fail(InvalidUser("You cannot share a review you own with yourself")).when(userId == s.userId)
    _      <- validateReviewPermissions(userId, s.reviewId) <&> validateUser(s.userId)
    result <- DatabaseService.shareReview(s)
  } yield result

  private def validateEntity(
      entityId: String,
      entityType: EntityType): ZIO[RequestSession[SpotifyService], Throwable | InvalidEntity, Unit] =
    RequestSession.get[SpotifyService].flatMap(_.isValidEntity(entityId, entityType)).flatMap {
      case true  => ZIO.unit
      case false => ZIO.fail(InvalidEntity(entityId, entityType))
    }

  private def validateReviewPermissions(userId: UserId, reviewId: UUID): ZIO[DatabaseService, Throwable | Forbidden, Unit] =
    DatabaseService.canModifyReview(userId, reviewId).flatMap {
      case true  => ZIO.unit
      case false => ZIO.fail(Forbidden(s"User $userId cannot modify review $reviewId"))
    }

  private def validateCommentEditingPermissions(
      userId: UserId,
      reviewId: UUID,
      commentId: Long): ZIO[DatabaseService, Throwable | Forbidden, Unit] =
    DatabaseService.canModifyComment(userId, reviewId, commentId).flatMap {
      case true  => ZIO.unit
      case false => ZIO.fail(Forbidden(s"User $userId cannot modify comment $commentId"))
    }

  private def validateCommentPermissions(userId: UserId, reviewId: UUID): ZIO[DatabaseService, Throwable | Forbidden, Unit] = {
    DatabaseService.canMakeComment(userId, reviewId).flatMap {
      case true  => ZIO.unit
      case false => ZIO.fail(Forbidden(s"User $userId cannot comment on review $reviewId"))
    }
  }

  private def validateUser(userId: UserId): ZIO[DatabaseService, Throwable | InvalidUser, Unit] =
    DatabaseService.getUserById(userId).flatMap {
      case Some(_) => ZIO.unit
      case None    => ZIO.fail(InvalidUser(userId))
    }
}
