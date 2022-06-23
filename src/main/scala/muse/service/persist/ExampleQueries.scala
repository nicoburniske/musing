package muse.service.persist

import io.getquill.*
import muse.domain.common.EntityType
import muse.domain.mutate.CreateReview
import muse.utils.Parallel
import zio.Console.*
import zio.*

object ExampleQueries extends ZIOAppDefault {
  override def run = {
    val layers  = ZEnv.live ++ (QuillContext.dataSourceLayer >+> DatabaseQueries.live)
    val program = for {
      user <- DatabaseQueries.getUserReviews("tuckingfypo1")
      _    <- printLine(s"User: $user")
      _    <- DatabaseQueries.createReview(
                "notarealuser",
                CreateReview("I'm not real", true, EntityType.Artist, "3FjdJbt6Myq32uv7P4owM1"))
      _    <- DatabaseQueries.createReview(
                "tuckingfypo1",
                CreateReview(
                  "Adrian's Impeccable taste in music",
                  true,
                  EntityType.Playlist,
                  "2nolvatuHnfTpHhXDOThrm"))
      _    <- DatabaseQueries.createReview(
                "tuckingfypo1",
                CreateReview(
                  "are alden's bumps all that hard?",
                  true,
                  EntityType.Playlist,
                  "5AGvFHPvaNS8SgVb9xAax3"))
      _    <- DatabaseQueries.createReview(
                "tuckingfypo1",
                CreateReview("Is RadioHead Overrated?", true, EntityType.Album, "1HrMmB5useeZ0F5lHrMvl0"))
      _    <- DatabaseQueries.createReview(
                "tuckingfypo1",
                CreateReview("RICEWINE might be a genius", true, EntityType.Track, "0gdWSthwNMJ4TPVya8b0bh"))
      _    <- DatabaseQueries.createReview(
                "tuckingfypo1",
                CreateReview("The next big thing", true, EntityType.Artist, "68YGyJRfQQG9HqiMpgoRiV"))
      _    <- DatabaseQueries.createReview(
                "tuckingfypo1",
                CreateReview("Argentine Rock Icon", true, EntityType.Track, "6XoyfaS2X22S3IwzYJwPzd")
              )
      user <- DatabaseQueries.getUserReviews("tuckingfypo1")
      _    <- printLine(s"User: $user")
    } yield ()
    program.provide(layers)
  }

}
