package persist

import domain.common.EntityType
import domain.create.CreateReview
import zio.ZIOAppDefault
import zio.*
import zio.Console.*
import io.getquill.*
import utils.Parallel


object ExampleQueries extends ZIOAppDefault {
  override def run = {
    val layers  = ZEnv.live ++ (QuillContext.dataSourceLayer >+> DatabaseQueries.live)
    val program = for {
      user <- DatabaseQueries.getUserReviews("hondosin")
      _    <- printLine(s"User: $user")
      _    <- DatabaseQueries.createReview(CreateReview("hondosin", false, EntityType.Playlist, "Balcony Bumps"))
      user <- DatabaseQueries.getUserReviews("hondosin")
      _    <- printLine(s"User: $user")
    } yield ()
    program.provide(layers)
  }

}
