/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.sharding.typed;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.ActorContext;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import akka.cluster.sharding.typed.javadsl.PersistentEntity;
import akka.cluster.sharding.typed.javadsl.ShardedEntity;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.Effect;
import akka.persistence.typed.javadsl.EventHandler;
import akka.util.Timeout;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletionStage;

public class HelloWorldPersistentEntityExample {


  public static class HelloWorldService {
    private final ActorSystem<?> system;
    private final ClusterSharding sharding;
    private final Timeout askTimeout = Timeout.create(Duration.ofSeconds(5));

    // registration at startup
    public HelloWorldService(ActorSystem<?> system) {
      this.system = system;
      sharding = ClusterSharding.get(system);

      sharding.start(
          ShardedEntity.ofPersistentEntity(
              HelloWorld.ENTITY_TYPE_KEY,
              ctx -> new HelloWorld(ctx.actorContext(), ctx.entityId()),
              Passivate.INSTANCE));
    }

    // usage example
    public CompletionStage<Integer> sayHello(String worldId, String whom) {
      EntityRef<HelloWorldCommand> entityRef = sharding.entityRefFor(HelloWorld.ENTITY_TYPE_KEY, worldId);
      CompletionStage<Greeting> result =
          entityRef.ask(replyTo -> new Greet(whom, replyTo), askTimeout);
      return result.thenApply(greeting -> greeting.numberOfPeople);
    }
  }

  // Command
  interface HelloWorldCommand {}
  public static final class Greet implements HelloWorldCommand {
    public final String whom;
    public final ActorRef<Greeting> replyTo;

    public Greet(String whom, ActorRef<Greeting> replyTo) {
      this.whom = whom;
      this.replyTo = replyTo;
    }
  }
  enum Passivate implements HelloWorldCommand {
    INSTANCE
  }

  // Response
  public static final class Greeting {
    public final String whom;
    public final int numberOfPeople;

    public Greeting(String whom, int numberOfPeople) {
      this.whom = whom;
      this.numberOfPeople = numberOfPeople;
    }
  }

  // Event
  public static final class Greeted {
    public final String whom;

    public Greeted(String whom) {
      this.whom = whom;
    }
  }

  // State
  private static final class KnownPeople {
    private Set<String> names = Collections.emptySet();

    KnownPeople() {
    }

    private KnownPeople(Set<String> names) {
      this.names = names;
    }

    KnownPeople add(String name) {
      Set<String> newNames = new HashSet<>(names);
      newNames.add(name);
      return new KnownPeople(newNames);
    }

    int numberOfPeople() {
      return names.size();
    }
  }

  public static class HelloWorld extends PersistentEntity<HelloWorldCommand, Greeted, KnownPeople> {

    public static final EntityTypeKey<HelloWorldCommand> ENTITY_TYPE_KEY =
        EntityTypeKey.create(HelloWorldCommand.class, "HelloWorld");

    public HelloWorld(ActorContext<HelloWorldCommand> ctx, String entityId) {
      super(ENTITY_TYPE_KEY, entityId);
    }

    @Override
    public KnownPeople emptyState() {
      return new KnownPeople();
    }

    @Override
    public CommandHandler<HelloWorldCommand, Greeted, KnownPeople> commandHandler() {
      return commandHandlerBuilder(KnownPeople.class)
          .matchCommand(Greet.class, this::greet)
          .matchCommand(Greet.class, this::passivate)
          .build();
    }

    private Effect<Greeted, KnownPeople> passivate(KnownPeople state, HelloWorldCommand cmd) {
      return Effect().stop();
    }

    private Effect<Greeted, KnownPeople> greet(KnownPeople state, Greet cmd) {
      return Effect().persist(new Greeted(cmd.whom))
        .andThen(newState -> cmd.replyTo.tell(new Greeting(cmd.whom, newState.numberOfPeople())));
    }

    @Override
    public EventHandler<KnownPeople, Greeted> eventHandler() {
      return this::applyEvent;
    }

    private KnownPeople applyEvent(KnownPeople state, Greeted evt) {
      return state.add(evt.whom);
    }
  }
}
