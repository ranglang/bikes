package akka.sample.bikes

import java.util.concurrent.TimeUnit

import akka.actor.typed._
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, LoggerOps }
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, EntityTypeKey }
import akka.cluster.typed.Cluster
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import akka.persistence.typed.{ PersistenceId, RecoveryCompleted, SnapshotSelectionCriteria }
import JsonSupport._
import Procurement._
import akka.sample.bikes.tree.{ GlobalTreeActor, NodePath }
import spray.json._

import scala.concurrent.duration.FiniteDuration

/**
 * Bike FSM.
 */
object Bike {
  val typeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Bike")

  sealed trait Event
  final case class DownloadEvent(blueprint: Blueprint) extends Event
  final case class DownloadedEvt(blueprint: Blueprint) extends Event
  final case class CreateEvent(blueprint: Blueprint) extends Event
  final case class CreatedEvt(blueprint: Blueprint) extends Event
  final case class ReserveEvent(blueprint: Blueprint) extends Event
  final case class ReservedEvt(blueprint: Blueprint) extends Event
  final case class YieldEvent(blueprint: Blueprint) extends Event
  final case class YieldedEvt(blueprint: Blueprint) extends Event
  final case class KickEvent(previousState: State) extends Event
  final case class ErrorEvent(errorMessage: String, previousState: State, causeCommand: Command) extends Event

  /**
   * This interface defines all the commands that the persistent actor supports.
   */
  sealed trait Command
  final case object Idle extends Command
  final case object GoodBye extends Command
  final case class DownloadCmd(blueprint: Blueprint) extends Command
  final case class CreateCmd(blueprint: Blueprint) extends Command
  final case object ReserveCmd extends Command
  final case object YieldCmd extends Command
  final case object KickCmd extends Command
  final case class GetStateCmd(bikeId: String, replyTo: ActorRef[BikeRoutes.QueryStatus]) extends Command
  final case object Timeout extends Command
  /**
   * The purpose of a wrapping class like this is to avoid circular dependencies between sender and receiver actors.
   * Here, `Bike` depends on an external service, `Procurement`, because it needs to send messages to `Procurement`:
   * ```
   * bike ! Procurement.SomeOperation
   * ```
   * `Procurement` needs to respond to those messages:
   * ```
   * bike ! Bike.Response
   * ```
   * causing `Procurement` to depend on `Bike`.
   *
   * Instead of depending on `Bike` that way, we can use an adapter and have `Procurement` do: `actorRef ! Procurement.Reply`,
   * where actorRef is meant to be a bike.
   *
   * Here is how it goes: the bike sends a message to `Procurement`:
   * ```
   * procurement ! Procurement.SomeOperation(cmd.blueprint, replyToMapper, "download()")
   * ```
   * where `replyToMapper` is this:
   * ```
   * val replyToMapper: ActorRef[Reply] = context.messageAdapter(reply => AdaptedReply(reply))
   * ```
   * All `Procurement` knows is that it replies to an `ActorRef[Reply]`, where `Reply` is defined by `Procurement`.
   * In reality, `Procurement` sends a message to an adapter, `replyMapper`, which will take a `Reply` and transform it into a
   * `AdaptedReply`, which is something understood by `Bike`. So, in the end, all of this happens as if `Procurement`
   * sent a `AdaptedReply` message to `Bike`, as if it were: `procurement ! Bike.AdaptedReply`.
   * There is just an adapter in the middle.
   *
   * @param response response from external service
   */
  private final case class AdaptedReply(response: Procurement.Reply) extends Command

  sealed trait State
  final case object InitState extends State
  final case class DownloadingState(blueprint: Blueprint) extends State
  final case class DownloadedState(blueprint: Blueprint) extends State
  final case class CreatingState(blueprint: Blueprint) extends State
  final case class CreatedState(blueprint: Blueprint) extends State
  final case class ReservingState(blueprint: Blueprint) extends State
  final case class ReservedState(blueprint: Blueprint) extends State
  final case class YieldingState(blueprint: Blueprint) extends State
  final case class YieldedState(blueprint: Blueprint) extends State
  final case class ErrorState(msg: String, offendingCommand: Command, lastState: State) extends State {
    override def toString: String = s"ErrorState('$msg', ${offendingCommand.getClass.getSimpleName}, ${lastState.getClass.getSimpleName})"
  }

  private case object TimerKey

  /** Represents the coordinates of a resource, the unique way to identify a certain resource like blueprint parts. */
  final case class NiUri(version: String, location: String)
  type Token = String

  private implicit def convertState(state: State) = {
    val st = state.getClass.getSimpleName
    if (st.endsWith("$")) st.replace("$", "") else st
  }
  def displayOfId(bikeId: String): String = {
    val index = bikeId.lastIndexOf("-")
    bikeId.substring(0, if (index != -1) index else bikeId.length)
  }
  final case class Blueprint(instructions: NiUri, bom: NiUri = NiUri("", ""), mechanic: NiUri = NiUri("", ""), access: Token = "") {
    def displayId: String = displayOfId(instructions.version)
    def makeEntityId(): String = instructions.toJson.convertTo[NiUri].version
  }

  /**
   * Finds (memberId, shardId, bikeId) given bikeId and ActorSystem.
   * Shard id is easily found from the entity id by using the sharding function.
   * Member id is known from the system.
   *
   * The tree model in GlobalTreeActor is not the real model (the cluster), but a copy
   * of it. It would be great to give d3.js the correct model from jmx or something else, but from the cluster itself,
   * without having to create a Tree copy structure.
   *
   * @param bikeId entity id for the bike
   * @param system actor system
   * @return
   */
  private def fullPath(bikeId: String, system: ActorSystem[_])(implicit numOfShards: Int): NodePath = {
    val shardId = BikeMessageExtractor.consHash(bikeId, numOfShards)
    val memberId = Cluster.get(system).selfMember.address.toString
    NodePath(memberId, shardId, bikeId)
  }
  private def fullPath(blueprint: Blueprint, system: ActorSystem[_])(implicit numOfShards: Int): NodePath = {
    val bikeId = blueprint.makeEntityId()
    fullPath(bikeId, system)
  }

  def apply(bikeId: String, ops: ActorRef[Operation], globalTreeRef: ActorRef[GlobalTreeActor.TreeCommand],
    shard: ActorRef[ClusterSharding.ShardCommand], numOfShards: Int): Behavior[Command] = {
    implicit val ns = numOfShards
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        context.log.info("STARTING: {}", bikeId)
        val path = fullPath(bikeId, context.system)
        globalTreeRef ! GlobalTreeActor.AddEntity(path, InitState)

        val fsmTimeout = context.system.settings.config.getDuration("bikes.fsm-timeout").toMillis
        val timeout = FiniteDuration(fsmTimeout, TimeUnit.MILLISECONDS)
        timers.startSingleTimer(TimerKey, Timeout, timeout)

        val replyToMapper: ActorRef[Reply] = context.messageAdapter(AdaptedReply)

        def active(): Behavior[Command] =
          EventSourcedBehavior[Command, Event, State](
            persistenceId = PersistenceId(typeKey.name, bikeId),
            emptyState = InitState,
            commandHandler(context, ops, replyToMapper, globalTreeRef, shard, bikeId), //commandHandler, given a context, is a function: (State, Operation) => Effect[Event, State],
            eventHandler(context, bikeId))
            .receiveSignal {
              case (state, RecoveryCompleted) =>
                context.log.info("Bike {} is RECOVERED, entity id {}, state {}", context.self, bikeId, state.getClass.getSimpleName)
                val path = fullPath(bikeId, context.system)
                globalTreeRef ! GlobalTreeActor.AddEntity(path, state)
                Behaviors.same
              case (state, PostStop) =>
                context.log.info("Bike {}\n\t\twith state {}\n\t\twith actor ref {}\n\t\tis now STOPPED", bikeId, state.getClass.getName, context.self)
                val path = fullPath(bikeId, context.system)
                globalTreeRef ! GlobalTreeActor.RemoveEntity(path)
                Behaviors.same
            }.withSnapshotSelectionCriteria(SnapshotSelectionCriteria.none)

        //This is a timeout for entities that have been idle for while. It is different from fsmTimeout.
        //See https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html?#passivation
        val receiveTimeout = FiniteDuration(
          context.system.settings.config.getDuration("bikes.receive-timeout").toMillis,
          TimeUnit.MILLISECONDS)
        context.setReceiveTimeout(receiveTimeout, Idle)

        active()
      }
    }
  }

  private def commandHandler(context: ActorContext[Command], ops: ActorRef[Operation],
    replyToMapper: ActorRef[Reply], globalTreeRef: ActorRef[GlobalTreeActor.TreeCommand],
    shard: ActorRef[ClusterSharding.ShardCommand], bikeId: String)(implicit numOfShards: Int): (State, Command) => Effect[Event, State] = { (state, command) =>

    def download(cmd: DownloadCmd): Effect[Event, State] = {
      ops ! SomeOperation(cmd.blueprint, replyToMapper, "download()")
      val evt = DownloadEvent(cmd.blueprint)
      Effect.persist(evt).thenRun { newState =>
        val path = fullPath(cmd.blueprint.makeEntityId(), context.system)
        context.log.infoN("Blueprint {} with\n\t\t\tmember {},\n\t\t\tshard {}\n\t\t\tis being downloaded", cmd.blueprint.displayId, path.memberId, path.shardId)
        globalTreeRef ! GlobalTreeActor.AddEntity(path, newState)
      }
    }

    def downloaded(reply: Reply): Effect[Event, State] = reply match {
      case OpCompleted(blueprint) =>
        val evt = DownloadedEvt(blueprint)
        Effect.persist(evt).thenRun { newState =>
          context.log.info2("Blueprint {} downloaded, state is now {} ", blueprint.displayId, newState.getClass.getSimpleName)
          val path = fullPath(blueprint, context.system)
          // todo (optional): can possibly use [Replies](https://doc.akka.io/docs/akka/current/typed/persistence.html#replies)
          globalTreeRef ! GlobalTreeActor.AddEntity(path, newState)
          context.self ! CreateCmd(blueprint)
        }

      case OpFailed(blueprint, errorMessage) =>
        val evt = ErrorEvent(errorMessage, InitState, DownloadCmd(blueprint))
        Effect.persist(evt).thenRun { newState: State =>
          context.log.info2("ERROR while downloading blueprint {}, state is now {} ", blueprint.displayId, newState.getClass.getSimpleName)
          val path = fullPath(blueprint, context.system)
          globalTreeRef ! GlobalTreeActor.AddEntity(path, newState)
        } //.thenStop()
    }

    def create(cmd: CreateCmd): Effect[Event, State] = {
      ops ! SomeOperation(cmd.blueprint, replyToMapper, "create()")
      val evt = CreateEvent(cmd.blueprint)
      Effect.persist(evt).thenRun { newState =>
        context.log.info("Bike {} is being created ", cmd.blueprint.displayId)
        val path = fullPath(bikeId, context.system)
        globalTreeRef ! GlobalTreeActor.AddEntity(path, newState)
      }
    }

    def created(reply: Reply): Effect[Event, State] = reply match {
      case OpCompleted(blueprint) =>
        val evt = CreatedEvt(blueprint)
        Effect.persist(evt).thenRun { newState =>
          context.log.info2("Bike {} created, state is now {} ", blueprint.displayId, newState.getClass.getSimpleName)
          val path = fullPath(blueprint, context.system)
          globalTreeRef ! GlobalTreeActor.AddEntity(path, newState)
          context.self ! ReserveCmd
        }

      case OpFailed(blueprint, errorMessage) =>
        val evt = ErrorEvent(errorMessage, DownloadedState(blueprint), CreateCmd(blueprint))
        Effect.persist(evt).thenRun { newState: State =>
          context.log.info2("ERROR while creating bike {}, state is now {} ", blueprint.displayId, newState.getClass.getSimpleName)
          val path = fullPath(blueprint, context.system)
          globalTreeRef ! GlobalTreeActor.AddEntity(path, newState)
        }
    }

    def getState(id: String, state: State, replyTo: ActorRef[BikeRoutes.QueryStatus]): Effect[Event, State] = {
      replyTo ! BikeRoutes.QueryStatus(id, state)
      Effect.none
    }

    def reserve(blueprint: Blueprint): Effect[Event, State] = {
      ops ! SomeOperation(blueprint, replyToMapper, "reserve()")
      val evt = ReserveEvent(blueprint)
      Effect.persist(evt).thenRun { newState =>
        context.log.info("Bike {} is being reserved ", blueprint.displayId)
        val path = fullPath(bikeId, context.system)
        globalTreeRef ! GlobalTreeActor.AddEntity(path, newState)
      }
    }

    def reserved(reply: Reply): Effect[Event, State] = reply match {
      case OpCompleted(blueprint) =>
        val evt = ReservedEvt(blueprint)
        Effect.persist(evt).thenRun { newState =>
          context.log.info2("Bike {} reserved, state is now {}", blueprint.displayId, newState.getClass.getSimpleName)
          val path = fullPath(blueprint, context.system)
          globalTreeRef ! GlobalTreeActor.AddEntity(path, newState)
        }

      case OpFailed(blueprint, errorMessage) =>
        val evt = ErrorEvent(errorMessage, CreatedState(blueprint), ReserveCmd)
        Effect.persist(evt).thenRun { newState: State =>
          context.log.info2("ERROR while reserving Bike {}, state is now {} ", blueprint.displayId, newState.getClass.getSimpleName)
          val path = fullPath(blueprint, context.system)
          globalTreeRef ! GlobalTreeActor.AddEntity(path, newState)
        }
    }

    def `yield`(blueprint: Blueprint): Effect[Event, State] = {
      ops ! SomeOperation(blueprint, replyToMapper, "yield op")
      val evt = YieldEvent(blueprint)
      Effect.persist(evt).thenRun { newState =>
        context.log.info("Bike {} is being yielded ", blueprint.displayId)
        val path = fullPath(bikeId, context.system)
        globalTreeRef ! GlobalTreeActor.AddEntity(path, newState)
      }
    }

    def yielded(reply: Reply): Effect[Event, State] = reply match {
      case OpCompleted(blueprint) =>
        val evt = YieldedEvt(blueprint)
        Effect.persist(evt).thenRun { newState =>
          context.log.info2("Bike {} yielded, state is now {}", blueprint.displayId, newState.getClass.getSimpleName)
          val path = fullPath(blueprint, context.system)
          globalTreeRef ! GlobalTreeActor.AddEntity(path, newState)
        }

      case OpFailed(blueprint, errorMessage) =>
        val evt = ErrorEvent(errorMessage, ReservedState(blueprint), YieldCmd)
        Effect.persist(evt).thenRun { newState: State =>
          context.log.info2("ERROR while reserving bike {}, state is now {} ", blueprint.displayId, newState.getClass.getSimpleName)
          val path = fullPath(blueprint, context.system)
          globalTreeRef ! GlobalTreeActor.AddEntity(path, newState)
        }
    }

    def kickIt(commandToReIssue: Command, previousState: State): Effect[Event, State] = {
      val evt = KickEvent(previousState)
      Effect.persist(evt).thenRun { newState =>
        context.log.info2("Blocked bike kicked, state is now {}, commandToReIssue {} ", newState.getClass.getSimpleName, commandToReIssue.getClass.getSimpleName)
        val path = fullPath(bikeId, context.system)
        globalTreeRef ! GlobalTreeActor.AddEntity(path, newState)
        context.log.info2("About to reissue command {} from state {} ", commandToReIssue.getClass.getSimpleName, newState.getClass.getSimpleName)
        context.self ! commandToReIssue
      }
    }

    (state, command) match {
      case (_, GetStateCmd(bikeId, replyTo)) =>
        context.log.debug("GET Bike state: {}", state.getClass.getSimpleName)
        getState(bikeId, state, replyTo)
      case (_, Idle) =>
        context.log.debug("Received IDLE MESSAGE timeout for bike {} ", bikeId)
        shard ! ClusterSharding.Passivate(context.self)
        Effect.none
      case (st, Timeout) =>
        def goBack(stateToGoBackTo: State, commandToReIssue: Command) = {
          context.log.debug("Bike {} has been hanging (not reserved nor yielded) for too long", bikeId)
          val evt = ErrorEvent("Processing took too long: timed out", stateToGoBackTo, commandToReIssue)
          Effect.persist(evt).thenRun { newState: State =>
            context.log.info("Moved bike {} to error state ", bikeId)
            val path = fullPath(bikeId, context.system)
            globalTreeRef ! GlobalTreeActor.AddEntity(path, newState)
          }
        }
        st match {
          case ReservedState(_) | YieldedState(_) | ErrorState(_, _, _) | InitState => Effect.none
          case DownloadingState(c) => goBack(InitState, DownloadCmd(c))
          case DownloadedState(c) => goBack(DownloadedState(c), CreateCmd(c))
          case CreatingState(c) => goBack(DownloadedState(c), CreateCmd(c))
          case CreatedState(c) => goBack(CreatedState(c), ReserveCmd)
          case ReservingState(c) => goBack(CreatedState(c), ReserveCmd)
          case YieldingState(c) => goBack(ReservedState(c), YieldCmd)
        }

      case (_, GoodBye) =>
        // the stopMessage, used for rebalance and passivate
        context.log.debug("Received STOP MESSAGE for bike {} ", bikeId)
        val path = fullPath(bikeId, context.system)
        globalTreeRef ! GlobalTreeActor.RemoveEntity(path)
        Effect.stop()

      case (state, command) =>
        state match {
          case InitState =>
            command match {
              case cmd: DownloadCmd => download(cmd)
              case _ => Effect.unhandled
            }

          case _: DownloadingState =>
            command match {
              case AdaptedReply(reply) => downloaded(reply)
              case _ => Effect.unhandled
            }

          case _: DownloadedState =>
            command match {
              case cmd: CreateCmd => create(cmd)
              case _ => Effect.unhandled
            }

          case _: CreatingState =>
            command match {
              case AdaptedReply(reply) => created(reply)
              case _ => Effect.unhandled
            }

          case CreatedState(blueprint) =>
            command match {
              case ReserveCmd => reserve(blueprint)
              case _ => Effect.unhandled
            }

          case YieldedState(blueprint) =>
            command match {
              case ReserveCmd => reserve(blueprint)
              case _ => Effect.unhandled
            }

          case _: ReservingState =>
            command match {
              case AdaptedReply(reply) => reserved(reply)
              case _ => Effect.unhandled
            }

          case ReservedState(blueprint) =>
            command match {
              case YieldCmd => `yield`(blueprint)
              case _ => Effect.unhandled
            }

          case _: YieldingState =>
            command match {
              case AdaptedReply(reply) => yielded(reply)
              case _ => Effect.unhandled
            }

          case ErrorState(_, offendingCommand, previousState) =>
            command match {
              case KickCmd => kickIt(offendingCommand, previousState)
              case _ => Effect.unhandled
            }
        }
    }
  }

  private def eventHandler(context: ActorContext[Command], bikeId: String): (State, Event) => State = { (state, event) =>
    context.log.debug2("State for {} is now: {}", bikeId, state.getClass.getSimpleName)
    (state, event) match {

      case (_, ErrorEvent(errorMsg, errState, command)) =>
        ErrorState(errorMsg, command, errState)

      case (state, _) => state match {
        case InitState =>
          event match {
            case DownloadEvent(blueprint) => DownloadingState(blueprint);
            case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
          }

        case state: DownloadingState =>
          event match {
            case DownloadedEvt(blueprint) =>
              val st = DownloadedState(blueprint)
              context.log.debug("Going to state: {}", st.getClass.getSimpleName)
              st
            case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
          }

        case state: DownloadedState =>
          event match {
            case CreateEvent(blueprint) => CreatingState(blueprint)
            case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
          }

        case state: CreatingState =>
          event match {
            case CreatedEvt(id) => CreatedState(id)
            case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
          }

        case state: CreatedState =>
          event match {
            case ReserveEvent(blueprint) => ReservingState(blueprint)
            case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
          }

        case state: YieldedState =>
          event match {
            case ReserveEvent(blueprint) => ReservingState(blueprint)
            case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
          }

        case state: ReservingState =>
          event match {
            case ReservedEvt(id) => ReservedState(id)
            case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
          }

        case state: ReservedState =>
          event match {
            case YieldEvent(blueprint) => YieldingState(blueprint)
            case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
          }

        case state: YieldingState =>
          event match {
            case YieldedEvt(blueprint) => YieldedState(blueprint)
            case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
          }

        case ErrorState(_, _, _) =>
          event match {
            case KickEvent(previousState) =>
              context.log.debug("Going to state: {}", previousState.getClass.getSimpleName)
              previousState
            case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
          }
      }
    }
  }
}