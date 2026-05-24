package marklit.sbt

import sbt._

import java.util.concurrent.ConcurrentHashMap

/** Process-wide registry of marklit daemons, one per `(jarPath,
  * idleTimeoutSeconds)` pair. The sbt JVM may host multiple loaded builds
  * across reloads; keying by jar path means a build reload reuses the same
  * daemon, but two builds pointing at different CLI jars stay isolated.
  *
  * `Global / onUnload` shuts down all entries — fires when the build session
  * ends or sbt itself exits.
  */
private[sbt] object MarklitDaemonRegistry {

  private val clients =
    new ConcurrentHashMap[Key, MarklitDaemonClient]()

  private case class Key(jarPath: String, idleTimeoutSeconds: Long)

  def get(
      marklitJar: File,
      idleTimeoutSeconds: Long,
      log: Logger
  ): MarklitDaemonClient = {
    val key = Key(marklitJar.getAbsolutePath, idleTimeoutSeconds)
    clients.computeIfAbsent(
      key,
      _ => new MarklitDaemonClient(marklitJar, idleTimeoutSeconds, log)
    )
  }

  /** Shut down every registered daemon. Idempotent. */
  def shutdownAll(): Unit = {
    val it = clients.values().iterator()
    while (it.hasNext) {
      val client = it.next()
      try client.shutdown()
      catch { case _: Throwable => () }
    }
    clients.clear()
  }
}
