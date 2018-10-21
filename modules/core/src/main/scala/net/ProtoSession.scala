package skunk
package net

import cats.{ Contravariant, Functor }
import cats.arrow.Profunctor
import cats.effect.{ Concurrent, ConcurrentEffect, Resource }
import cats.effect.concurrent.Semaphore
import cats.implicits._
import fs2.concurrent.Signal
import fs2.Stream
import skunk.data._
import skunk.net.message. { Query => QueryMessage, _ }
import skunk.util.Namer

/**
 * Represents a live connection to a Postgres database. This is a lifetime-managed resource and as
 * such is invalid outside the scope of its owning `Resource`, as are any streams yielded here. If
 * you construct a stream and never run it, no problem. But if you do run it you must do so while
 * the session is valid, and you must consume all input as it arrives.
 */
trait ProtoSession[F[_]] {

  /** Prepared statement yielding rows, dependently scoped to this `ProtoSession`. */
  sealed abstract case class PreparedQuery[A, B](name: String, query: Query[A, B]) {
    def dimap[C, D](f: C => A)(g: B => D): PreparedQuery[C,D] =
      new PreparedQuery(name, query.dimap(f)(g)) {}
  }
  object PreparedQuery {

    implicit val ProfunctorPreparedQuery: Profunctor[PreparedQuery] =
      new Profunctor[PreparedQuery] {
        def dimap[A, B, C, D](fab: PreparedQuery[A,B])(f: C => A)(g: B => D) =
          fab.dimap(f)(g)
      }

    // why don't we get these for free?

    implicit def functorPreparedQuery[T]: Functor[PreparedQuery[T, ?]] =
      new Functor[PreparedQuery[T, ?]] {
        def map[A, B](fa: PreparedQuery[T, A])(f: A => B) = fa.rmap(f)
      }

    implicit def contravariantPreparedQuery[T]: Contravariant[PreparedQuery[?, T]] =
      new Contravariant[PreparedQuery[?, T]] {
        def contramap[A, B](fa: PreparedQuery[A, T])(f: B => A) = fa.lmap(f)
      }

  }

  /** Prepared statement yielding no rows, dependently scoped to this `ProtoSession`. */
  sealed abstract case class PreparedCommand[A](name: String, command: Command[A]) {
    def contramap[B](f: B => A): PreparedCommand[B] =
      new PreparedCommand(name, command.contramap(f)) {}
  }

  sealed trait Portal {
    def name: String
  }

  /** Query portal with associated decoder, dependently scoped to this `ProtoSession`. */
  sealed abstract case class QueryPortal[A](name: String, decoder: Decoder[A]) extends Portal {
    def map[B](f: A => B): QueryPortal[B] =
      new QueryPortal[B](name, decoder.map(f)) {}
  }

  /** Command portal, dependently scoped to this `ProtoSession`. */
  sealed abstract case class CommandPortal(name: String) extends Portal

  def notifications(maxQueued: Int): Stream[F, Notification]
  def startup(user: String, database: String): F[Unit]
  def parameters: Signal[F, Map[String, String]]
  def transactionStatus: Signal[F, TransactionStatus]
  def quick[A](query: Query[Void, A]): F[List[A]]
  def quick(command: Command[Void]): F[Completion]
  def listen(channel: Identifier): F[Unit]
  def unlisten(channel: Identifier): F[Unit]
  def notify(channel: Identifier, message: String): F[Unit]
  def prepare[A, B](query: Query[A, B]): F[PreparedQuery[A, B]]
  def prepare[A](command: Command[A]): F[PreparedCommand[A]]
  def check[A, B](query: PreparedQuery[A, B]): F[Unit]
  def check[A](command: PreparedCommand[A]): F[Unit]

  def bind[A, B](pq: PreparedQuery[A, B], args: A): F[QueryPortal[B]]

  def bind[A](pq: PreparedCommand[A], args: A): F[CommandPortal]

  def close(p: Portal): F[Unit]
  def close(p: PreparedCommand[_]): F[Unit]
  def close(p: PreparedQuery[_, _]): F[Unit]

  def execute[A](portal: QueryPortal[A], maxRows: Int): F[List[A] ~ Boolean]
  def execute(portal: CommandPortal): F[Completion]

}

object ProtoSession {

  /**
   * Resource yielding a new `ProtoSession` with the given `host`, `port`, and statement checking policy.
   * @param host  Postgres server host
   * @param port  Postgres port, default 5432
   * @param check Check all `prepare` and `quick` statements for consistency with the schema. This
   *   is true by default and is recommended for development work. You may wish to turn this off in
   *   production but honestly it's really cheap and probably worth keeping.
   */
  def apply[F[_]: ConcurrentEffect](
    host:  String,
    port:  Int     = 5432,
    check: Boolean = true
  ): Resource[F, ProtoSession[F]] =
    for {
      ams <- ActiveMessageSocket[F](host, port)
      ses <- Resource.liftF(ProtoSession.fromActiveMessageSocket(ams, check))
    } yield ses

  /** Construct a `ProtoSession` by wrapping an `ActiveMessageSocket`. */
  private def fromActiveMessageSocket[F[_]: Concurrent](
    ams:   ActiveMessageSocket[F],
    check: Boolean
  ): F[ProtoSession[F]] =
    for {
      nam <- Namer[F]
      sem <- Semaphore[F](1)
    } yield new SessionImpl(ams, nam, sem, check)

  /**
   * `ProtoSession` implementation.
   * @param ams `ActiveMessageSocket` that manages message exchange.
   * @param nam `Namer` for giving unique (per session) names for prepared statements and portals.
   * @param sem Single-key `Semaphore` used as a mutex for message exchanges. Every "conversation"
   *   must be conducted while holding the mutex because we may have interleaved streams.
   * @param check Check all `prepare` and `quick` statements for consistency with the schema.
   */
  private final class SessionImpl[F[_]: Concurrent](
    ams:   ActiveMessageSocket[F],
    nam:   Namer[F],
    sem:   Semaphore[F],
    check: Boolean
  ) extends ProtoSession[F] {

    // Parameters and Transaction Status are freebies
    def parameters: Signal[F, Map[String, String]] = ams.parameters
    def transactionStatus: Signal[F, TransactionStatus] = ams.transactionStatus
    def notifications(maxQueued: Int): Stream[F, Notification] = ams.notifications(maxQueued)

    def close(p: Portal): F[Unit] =
      sem.withPermit {
        for {
          _ <- ams.send(Close.portal(p.name))
          _ <- ams.send(Flush)
          _ <- ams.expect { case CloseComplete => }
        } yield ()
      }

    def close(p: PreparedCommand[_]): F[Unit] =
      sem.withPermit {
        for {
          _ <- ams.send(Close.statement(p.name))
          _ <- ams.send(Flush)
          _ <- ams.expect { case CloseComplete => }
        } yield ()
      }

    def close(p: PreparedQuery[_, _]): F[Unit] =
      sem.withPermit {
        for {
          _ <- ams.send(Close.statement(p.name))
          _ <- ams.send(Flush)
          _ <- ams.expect { case CloseComplete => }
        } yield ()
      }

    def bind[A, B](pq: PreparedQuery[A, B], args: A): F[QueryPortal[B]] =
      sem.withPermit {
        for {
          pn <- nam.nextName("query-portal")
          _  <- ams.send(Bind(pn, pq.name, pq.query.encoder.encode(args)))
          _  <- ams.send(Flush)
          _  <- ams.expect { case BindComplete => }
        } yield new QueryPortal(pn, pq.query.decoder) {}
      }

    def bind[A](pq: PreparedCommand[A], args: A): F[CommandPortal] =
      sem.withPermit {
        for {
          pn <- nam.nextName("command-portal")
          _  <- ams.send(Bind(pn, pq.name, pq.command.encoder.encode(args)))
          _  <- ams.send(Flush)
          _  <- ams.expect { case BindComplete => }
        } yield new CommandPortal(pn) {}
      }

    def execute(portal: CommandPortal): F[Completion] =
      sem.withPermit {
        for {
          _  <- ams.send(Execute(portal.name, 0))
          _  <- ams.send(Flush)
          c  <- ams.expect { case CommandComplete(c) => c }
        } yield c
      }

    def execute[A](portal: QueryPortal[A], maxRows: Int): F[List[A] ~ Boolean] =
      sem.withPermit {
        for {
          _  <- ams.send(Execute(portal.name, maxRows))
          _  <- ams.send(Flush)
          rs <- unroll(portal.decoder)
        } yield rs
      }

    // Execute a query and unroll its rows into a List. If no rows are returned it's an error.
    def quick[B](query: Query[Void, B]): F[List[B]] =
      sem.withPermit {
        for {
          _  <- ams.send(QueryMessage(query.sql))
          rd <- ams.expect { case rd @ RowDescription(_) => rd }
          _  <- printStatement(query.sql).whenA(check)
          _  <- checkRowDescription(rd, query.decoder).whenA(check)
          rs <- unroll(query.decoder).map(_._1) // rs._2 will always be true here
          _  <- ams.expect { case ReadyForQuery(_) => }
        } yield rs
      }

    def quick(command: Command[Void]): F[Completion] =
      sem.withPermit {
        for {
          _ <- ams.send(QueryMessage(command.sql))
          _ <- printStatement(command.sql).whenA(check)
          c <- ams.expect { case CommandComplete(c) => c }
          _ <- ams.expect { case ReadyForQuery(_) => }
        } yield c
      }

    // Startup negotiation. Very basic right now.
    def startup(user: String, database: String): F[Unit] =
      sem.withPermit {
        for {
          _ <- ams.send(StartupMessage(user, database))
          _ <- ams.expect { case AuthenticationOk => }
          _ <- ams.expect { case ReadyForQuery(_) => }
        } yield ()
      }

    def notify(channel: Identifier, message: String): F[Unit] =
      sem.withPermit {
        for {
          _ <- ams.send(QueryMessage(s"NOTIFY ${channel.value}, '$message'")) // TODO: escape!
          _ <- ams.expect { case CommandComplete(Completion.Notify) => }
          _ <- ams.expect { case ReadyForQuery(_) => }
        } yield ()
      }

    def prepare[A, B](query: Query[A, B]): F[PreparedQuery[A, B]] =
      sem.withPermit {
        for {
          n <- nam.nextName("query")
          _ <- ams.send(Parse(n, query.sql, query.encoder.oids.toList)) // blergh
          _ <- ams.send(Flush)
          _ <- ams.expect { case ParseComplete => }
        } yield new PreparedQuery(n, query) {}
      } flatMap { pq => check(pq).whenA(check).as(pq) }

    def prepare[A](command: Command[A]): F[PreparedCommand[A]] =
      sem.withPermit {
        for {
          n <- nam.nextName("command")
          _ <- ams.send(Parse(n, command.sql, command.encoder.oids.toList)) // blergh
          _ <- ams.send(Flush)
          _ <- ams.expect { case ParseComplete => }
        } yield new PreparedCommand(n, command) {}
      } flatMap { pq => check(pq).whenA(check).as(pq) }

    private def unroll[A](dec: Decoder[A]): F[List[A] ~ Boolean] = {
      def go(accum: List[A]): F[List[A] ~ Boolean] =
        ams.receive.flatMap {
          case rd @ RowData(_)         => go(dec.decode(rd.fields) :: accum)
          case      CommandComplete(_) => (accum.reverse ~ false).pure[F]
          case      PortalSuspended    => (accum.reverse ~ true).pure[F]
        }
      go(Nil)
    }

    def listen(channel: Identifier): F[Unit] =
      sem.withPermit {
        for {
          _ <- ams.send(QueryMessage(s"LISTEN ${channel.value}"))
          _ <- ams.expect { case CommandComplete(Completion.Listen) => }
          _ <- ams.expect { case ReadyForQuery(_) => }
        } yield ()
      }

    def unlisten(channel: Identifier): F[Unit] =
      sem.withPermit {
        for {
          _ <- ams.send(QueryMessage(s"UNLISTEN ${channel.value}"))
          _ <- ams.expect { case CommandComplete(Completion.Unlisten) => }
          _ <- ams.expect { case ReadyForQuery(_) => }
        } yield ()
      }

    // Analyze a prepared statement and ensure that the asserted types are correct.
    def check[A, B](stmt: PreparedQuery[A, B]): F[Unit] =
      sem.withPermit {
        for {
          _  <- ams.send(Describe.statement(stmt.name))
          _  <- ams.send(Flush)
          pd <- ams.expect { case pd @ ParameterDescription(_) => pd }
          fs <- ams.expect { case rd @ RowDescription(_) => rd }
          _  <- printStatement(stmt.query.sql)
          _  <- checkParameterDescription(pd, stmt.query.encoder)
          _  <- checkRowDescription(fs, stmt.query.decoder)
        } yield ()
      }

    // Analyze a prepared statement and ensure that the asserted types are correct.
    def check[A](stmt: PreparedCommand[A]): F[Unit] =
      sem.withPermit {
        for {
          _  <- ams.send(Describe.statement(stmt.name))
          _  <- ams.send(Flush)
          pd <- ams.expect { case pd @ ParameterDescription(_) => pd }
          _  <- ams.expect { case NoData => }
          _  <- printStatement(stmt.command.sql)
          _  <- checkParameterDescription(pd, stmt.command.encoder)
        } yield ()
      }

    private def printStatement(sql: String): F[Unit] = {
      def print(a: Any) = Concurrent[F].delay(println(a))
      for {
        _  <- print("**")
        _  <- sql.lines.toList.traverse(s => print("** " + s))
        _  <- print("**")
      } yield ()
    }

    private def checkRowDescription(rd: RowDescription, dec: Decoder[_]): F[Unit] = {
      def print(a: Any) = Concurrent[F].delay(println(a))
      val assertedFieldTypes = dec.oids
      val fs = rd.oids
      for {
        _  <- print("** Fields:     asserted: " + assertedFieldTypes.map(_.name).mkString(", "))
        _  <- print("**               actual: " + fs.map(n => Type.forOid(n).getOrElse(s"«$n»")).mkString(", "))
        _  <- print("**")
      } yield ()
    }

    private def checkParameterDescription(pd: ParameterDescription, enc: Encoder[_]): F[Unit] = {
      def print(a: Any) = Concurrent[F].delay(println(a))
      val assertedParameterTypes = enc.oids
      val ps = pd.oids
      for {
        _  <- print("** Parameters: asserted: " + assertedParameterTypes.map(_.name).mkString(", "))
        _  <- print("**               actual: " + ps.map(n => Type.forOid(n).getOrElse(s"«$n»")).mkString(", "))
        _  <- print("**")
      } yield ()
    }


  }

}

