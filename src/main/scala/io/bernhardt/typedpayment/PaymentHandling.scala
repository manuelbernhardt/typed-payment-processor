package io.bernhardt.typedpayment

import java.util.UUID

import akka.actor.typed.receptionist.Receptionist.Listing
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import io.bernhardt.typedpayment.Configuration.{ConfigurationRequest, MerchantId, UserId}
import squants.market.Money

/**
 * Keeps track of available payment processors and delegates incoming requests to a dedicated, session-scoped actor
 */
object PaymentHandling {

  def apply(configuration: ActorRef[ConfigurationRequest]): Behavior[Command] =
    Behaviors.setup[Command] { context =>

      // subscribe to the processor reference updates we're interested in
      val listingAdapter: ActorRef[Receptionist.Listing] = context.messageAdapter { listing =>
        AddProcessorReference(listing)
      }
      context.system.receptionist ! Receptionist.Subscribe(CreditCardProcessor.Key, listingAdapter)

      def handleRequest(paymentProcessors: Set[Listing]): Behavior[Command] =
        Behaviors.receiveMessage {
          case AddProcessorReference(listing) =>
            handleRequest(paymentProcessors + listing)
          case paymentRequest: HandlePayment =>
            // generate a unique ID for this request
            val requestId = PaymentRequestHandler.PaymentRequestId(UUID.randomUUID().toString)

            // spawn one child per request
            val requestHandler = context.spawn(
              PaymentRequestHandler(paymentRequest.sender, configuration, paymentProcessors),
              requestId.id
            )
            requestHandler ! PaymentRequestHandler.HandlePaymentRequest(requestId, paymentRequest.amount, paymentRequest.merchantId, paymentRequest.userId)
            Behaviors.same
        }

      // initial behavior
      handleRequest(Set.empty)
    }

  // ~~~ public protocol
  sealed trait Command
  case class HandlePayment(amount: Money, merchantId: MerchantId, userId: UserId, sender: ActorRef[PaymentRequestHandler.Response]) extends Command

  // ~~~ internal protocol
  sealed trait InternalMessage extends Command
  case class AddProcessorReference(listing: Receptionist.Listing) extends InternalMessage

}