package lila.game

import akka.actor._
import akka.pattern.pipe
import com.typesafe.config.Config
import scala.concurrent.duration._

import lila.common.PimpedConfig._

final class Env(
    config: Config,
    db: lila.db.Env,
    mongoCache: lila.memo.MongoCache.Builder,
    system: ActorSystem,
    hub: lila.hub.Env,
    getLightUser: String => Option[lila.common.LightUser],
    appPath: String,
    isProd: Boolean,
    scheduler: lila.common.Scheduler) {

  private val settings = new {
    val CachedNbTtl = config duration "cached.nb.ttl"
    val PaginatorMaxPerPage = config getInt "paginator.max_per_page"
    val CaptcherName = config getString "captcher.name"
    val CaptcherDuration = config duration "captcher.duration"
    val CollectionGame = config getString "collection.game"
    val CollectionCrosstable = config getString "collection.crosstable"
    val JsPathRaw = config getString "js_path.raw"
    val JsPathCompiled = config getString "js_path.compiled"
    val UciMemoTtl = config duration "uci_memo.ttl"
    val netBaseUrl = config getString "net.base_url"
    val PngUrl = config getString "png.url"
    val PngSize = config getInt "png.size"
  }
  import settings._

  lazy val gameColl = db(CollectionGame)

  lazy val playTime = new PlayTime(gameColl)

  lazy val pngExport = new PngExport(PngUrl, PngSize)

  lazy val divider = new Divider

  lazy val cached = new Cached(
    coll = gameColl,
    mongoCache = mongoCache,
    defaultTtl = CachedNbTtl)

  lazy val paginator = new PaginatorBuilder(
    coll = gameColl,
    cached = cached,
    maxPerPage = PaginatorMaxPerPage)

  lazy val rewind = Rewind

  lazy val uciMemo = new UciMemo(UciMemoTtl)

  lazy val pgnDump = new PgnDump(
    netBaseUrl = netBaseUrl,
    getLightUser = getLightUser)

  lazy val crosstableApi = new CrosstableApi(
    coll = db(CollectionCrosstable),
    gameColl = gameColl,
    system = system)

  // load captcher actor
  private val captcher = system.actorOf(Props(new Captcher), name = CaptcherName)

  val recentGoodGameActor = system.actorOf(Props[RecentGoodGame], name = "recent-good-game")
  system.lilaBus.subscribe(recentGoodGameActor, 'finishGame)

  scheduler.message(CaptcherDuration) {
    captcher -> actorApi.NewCaptcha
  }

  def onStart(gameId: String) = GameRepo game gameId foreach {
    _ foreach { game =>
      system.lilaBus.publish(actorApi.StartGame(game), 'startGame)
      game.userIds foreach { userId =>
        system.lilaBus.publish(
          actorApi.UserStartGame(userId, game),
          Symbol(s"userStartGame:$userId"))
      }
    }
  }

  lazy val stream = new GameStream(system)

  private def jsPath =
    "%s/%s".format(appPath, isProd.fold(JsPathCompiled, JsPathRaw))
}

object Env {

  lazy val current = "game" boot new Env(
    config = lila.common.PlayApp loadConfig "game",
    db = lila.db.Env.current,
    mongoCache = lila.memo.Env.current.mongoCache,
    system = lila.common.PlayApp.system,
    hub = lila.hub.Env.current,
    getLightUser = lila.user.Env.current.lightUser,
    appPath = play.api.Play.current.path.getCanonicalPath,
    isProd = lila.common.PlayApp.isProd,
    scheduler = lila.common.PlayApp.scheduler)
}
