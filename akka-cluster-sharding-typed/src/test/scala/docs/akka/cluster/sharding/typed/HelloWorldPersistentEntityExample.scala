/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.cluster.sharding.typed.scaladsl.PersistentEntity
import akka.cluster.sharding.typed.scaladsl.ShardedEntity
import akka.persistence.typed.scaladsl.Effect
import akka.util.Timeout

object HelloWorldPersistentEntityExample {

  class HelloWorldService(system: ActorSystem[_]) {
    import system.executionContext
    // registration at startup
    private val sharding = ClusterSharding(system)

    sharding.start(ShardedEntity(
      typeKey = HelloWorldEntityTypeKey,
      createBehavior = entityContext ⇒ helloWorldBehavior(entityContext.entityId),
      stopMessage = Passivate))

    private implicit val askTimeout: Timeout = Timeout(5.seconds)

    def greet(worldId: String, whom: String): Future[Int] = {
      val entityRef = sharding.entityRefFor(HelloWorldEntityTypeKey, worldId)
      val greeting = entityRef ? Greet(whom)
      greeting.map(_.numberOfPeople)
    }

  }

  // Command
  trait HelloWorldCommand
  final case class Greet(whom: String)(val replyTo: ActorRef[Greeting]) extends HelloWorldCommand
  case object Passivate extends HelloWorldCommand
  // Response
  final case class Greeting(whom: String, numberOfPeople: Int)

  // Event
  final case class Greeted(whom: String)

  // State
  private final case class KnownPeople(names: Set[String]) {
    def add(name: String): KnownPeople = copy(names = names + name)
    def numberOfPeople: Int = names.size
  }

  private val commandHandler: (KnownPeople, HelloWorldCommand) ⇒ Effect[Greeted, KnownPeople] = {
    (_, cmd) ⇒
      cmd match {
        case cmd: Greet ⇒ greet(cmd)
        case Passivate  ⇒ passivate()
      }
  }

  private def greet(cmd: Greet): Effect[Greeted, KnownPeople] =
    Effect.persist(Greeted(cmd.whom))
      .thenRun(state ⇒ cmd.replyTo ! Greeting(cmd.whom, state.numberOfPeople))

  private def passivate(): Effect[Greeted, KnownPeople] =
    Effect.stop

  private val eventHandler: (KnownPeople, Greeted) ⇒ KnownPeople = {
    (state, evt) ⇒ state.add(evt.whom)
  }

  val HelloWorldEntityTypeKey: EntityTypeKey[HelloWorldCommand] =
    EntityTypeKey[HelloWorldCommand]("HelloWorld")

  def helloWorldBehavior(entityId: String): Behavior[HelloWorldCommand] = PersistentEntity(
    entityTypeKey = HelloWorldEntityTypeKey,
    entityId = entityId,
    emptyState = KnownPeople(Set.empty),
    commandHandler,
    eventHandler
  )

}
