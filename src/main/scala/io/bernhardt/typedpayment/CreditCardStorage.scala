package io.bernhardt.typedpayment

import java.util.UUID

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }
import io.bernhardt.typedpayment.Configuration.{ CreditCardId, UserId }

object CreditCardStorage {
  sealed trait Command[Reply <: CommandReply] {
    def replyTo: ActorRef[Reply]
  }
  sealed trait Event
  sealed trait CommandReply

  // the protocol for adding credit cards
  final case class AddCreditCard(userId: UserId, last4Digits: String, replyTo: ActorRef[AddCreditCardResult])
      extends Command[AddCreditCardResult]
  final case class CreditCardAdded(id: CreditCardId, userId: UserId, last4Digits: String)
      extends Event
      with CborSerializable
  sealed trait AddCreditCardResult extends CommandReply
  case class Added(id: CreditCardId) extends AddCreditCardResult
  case object Duplicate extends AddCreditCardResult

  // the protocol for looking up credit cards by credit card id
  final case class FindById(id: CreditCardId, replyTo: ActorRef[FindCreditCardResult])
      extends Command[FindCreditCardResult]
  sealed trait FindCreditCardResult extends CommandReply
  case class CreditCardFound(card: StoredCreditCard) extends FindCreditCardResult
  case class CreditCardNotFound(id: CreditCardId) extends FindCreditCardResult

  // state definition
  final case class Storage(cards: Map[CreditCardId, StoredCreditCard] = Map.empty) {
    def applyEvent(event: Event): Storage = event match {
      case CreditCardAdded(id, userId, last4Digits) =>
        copy(cards = cards.updated(id, StoredCreditCard(id, userId, last4Digits)))
    }
    def applyCommand(context: ActorContext[Command[_]], cmd: Command[_]): ReplyEffect[Event, Storage] = cmd match {
      case AddCreditCard(userId, last4Digits, replyTo) =>
        val cardAlreadyExists = cards.values.exists(cc => cc.userId == userId && cc.last4Digits == last4Digits)
        if (cardAlreadyExists) {
          Effect.unhandled
            .thenRun { _: Storage =>
              context.log.warn("Tried adding already existing card")
            }
            .thenReply(replyTo)(_ => Duplicate)
        } else {
          val event = CreditCardAdded(CreditCardId(UUID.randomUUID().toString), userId, last4Digits)
          Effect.persist(event).thenReply(replyTo)(_ => Added(event.id))
        }
      case FindById(id, replyTo) if cards.contains(id) =>
        Effect.reply(replyTo)(CreditCardFound(cards(id)))
      case FindById(id, replyTo) if !cards.contains(id) =>
        Effect.reply(replyTo)(CreditCardNotFound(id))
    }
  }
  case class StoredCreditCard(id: CreditCardId, userId: UserId, last4Digits: String)

  def apply(): Behavior[Command[_]] = Behaviors.setup { context =>
    EventSourcedBehavior.withEnforcedReplies[Command[_], Event, Storage](
      persistenceId = PersistenceId.ofUniqueId("cc"),
      emptyState = Storage(),
      commandHandler = (state, cmd) => state.applyCommand(context, cmd),
      eventHandler = (state, evt) => state.applyEvent(evt))
  }
}
