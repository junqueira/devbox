package devbox
import java.io._
import java.nio.file.attribute.PosixFilePermission

import devbox.common._
import devbox.common.Cli.{Arg, showArg}
import Cli.pathScoptRead
import syncer.{AgentReadWriteActor, ReliableAgent, Syncer}

import scala.concurrent.ExecutionContext
object DevboxMain {
  case class Config(repo: List[String] = Nil,
                    stride: Int = 1,
                    debounceMillis: Int = 150,
                    help: Boolean = false,
                    logFile: Option[os.Path] = None,
                    ignoreStrategy: String = "",
                    readOnlyRemote: String = null,
                    healthCheckInterval: Int = 0,
                    retryInterval: Int = 0)

  def main(args: Array[String]): Unit = try {
    val signature = Seq(
      Arg[Config, String](
        "repo", None,
        "Which repository to sync",
        (c, v) => c.copy(repo = v :: c.repo)
      ),
      Arg[Config, Int](
        "debounce", None,
        "How many milliseconds to wait for the filesystem to stabilize before syncing",
        (c, v) => c.copy(stride = v)
      ),
      Arg[Config, Unit](
        "help", None,
        "Print this message",
        (c, v) => c.copy(help = true)
      ),
      Arg[Config, os.Path](
        "log-file", None,
        "Redirect logging output to a file",
        (c, v) => c.copy(logFile = Some(v))
      ),
      Arg[Config, String](
        "ignore-strategy", None,
        "",
        (c, v) => c.copy(ignoreStrategy = v)
      ),
      Arg[Config, String](
        "readonly-remote", None,
        "",
        (c, v) => c.copy(readOnlyRemote = v)
      ),
      Arg[Config, Int](
        "health-check-interval", None,
        "Interval between health check, health check should succeed before the next health check (in seconds)",
        (c, v) => c.copy(healthCheckInterval = v)
      )
    )


    Cli.groupArgs(args.toList, signature, Config()) match{
      case Left(msg) =>
        System.err.println(msg)
        System.exit(1)
      case Right((config, remaining)) =>
        if (config.help){
          val leftMargin = signature.map(showArg(_).length).max + 2
          System.out.println(Cli.formatBlock(signature, leftMargin).mkString("\n"))
        }else {

          implicit val ac = new castor.Context.Test(
            ExecutionContext.global,
            e => {
              e.printStackTrace()
              Util.sentryCapture(e)
            }
          )
          val (prep, connect) = remaining.splitAt(remaining.indexOf("--"))
          val logFile = config.logFile.getOrElse(throw new Exception("config.logFile is None"))
          val s"$logFileName.$logFileExt" = logFile.last
          val logFileBase = logFile / os.up

          implicit lazy val logger: devbox.logger.SyncLogger = new devbox.logger.SyncLogger.Impl(
            n => logFileBase / s"$logFileName$n.$logFileExt",
            50 * 1024 * 1024,
            new castor.ProxyActor((_: Unit) => AgentReadWriteActor.ForceRestart(), syncer.agentActor)
          )
          lazy val syncer = new Syncer(
            new ReliableAgent(prep, connect.drop(1) /* drop the -- */, os.pwd),
            for(s <- config.repo)
              yield s.split(':') match{
                case Array(src) => (os.Path(src, os.pwd), os.rel / os.Path(src, os.pwd).last)
                case Array(src, dest) => (os.Path(src, os.pwd), os.rel / dest.split('/'))
              },
            config.ignoreStrategy,
            config.debounceMillis,
            if (config.readOnlyRemote == null) (_, sig) => sig
            else {
              val (regexStr, negate) =
                if (config.readOnlyRemote.head != '!') (config.readOnlyRemote, false)
                else (config.readOnlyRemote.drop(1), true)
              val regex = com.google.re2j.Pattern.compile(regexStr)

              {
                case (path, Sig.File(perms, blockHashes, size))
                  if regex.matches(path.toString) ^ negate =>
                  Sig.File(
                    perms
                      - PosixFilePermission.GROUP_WRITE
                      - PosixFilePermission.OTHERS_WRITE
                      - PosixFilePermission.OWNER_WRITE,
                    blockHashes,
                    size
                  )
                case (path, sig) => sig
              }
            }
          )
          Util.autoclose(syncer){syncer =>
            syncer.start()
            Thread.sleep(Long.MaxValue)
          }
        }
        System.exit(0)
    }

  }catch{case e: Throwable =>
    Util.sentryCapture(e)
    // Give Sentry Client a few moments to upload stuff before we exit the program
    // This is best effort: if it doesn't manage to upload in time, so be it.
    Thread.sleep(100)
    throw e
  }
}
