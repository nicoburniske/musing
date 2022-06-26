package muse.server.graphql

import caliban.schema.Annotations.GQLDefault
import muse.domain.common.EntityType
import muse.server.graphql.resolver.{GetReview, GetSearch, GetUser}
import muse.server.graphql.subgraph.{Review, SearchResult, User}
import muse.server.graphql.Pagination.Default
import muse.service.persist.DatabaseOps
import muse.service.spotify.SpotifyService
import zio.query.ZQuery

import java.util.UUID

final case class UserArgs(id: String)

final case class ReviewsArgs(id: UUID)

final case class SearchArgs(
    query: String,
    types: Set[EntityType],
    @GQLDefault(Default.Search.annotation) pagination: Option[Pagination])

final case class Queries(
    user: UserArgs => ZQuery[DatabaseOps, Throwable, User],
    reviews: ReviewsArgs => ZQuery[DatabaseOps, Throwable, Option[Review]],
    search: SearchArgs => ZQuery[SpotifyService, Throwable, SearchResult])

object Queries {
  val live = Queries(
    args => GetUser.query(args.id),
    args => GetReview.query(args.id),
    args => GetSearch.query(args.query, args.types, args.pagination.getOrElse(Default.Search))
  )
}
