package muse.domain.spotify
import zio.json.*

final case class InitialAuthData(
    @jsonField("token_type") tokenType: String,
    @jsonField("access_token") accessToken: String,
    @jsonField("refresh_token") refreshToken: String,
    @jsonField("scope") scope: String,
    @jsonField("expires_in") expiresIn: Int)

object InitialAuthData {
  given decoder: JsonCodec[InitialAuthData] = DeriveJsonCodec.gen[InitialAuthData]
}
