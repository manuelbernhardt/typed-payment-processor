package io.bernhardt.typedpayment

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import io.bernhardt.typedpayment.Configuration.{ConfigurationMessage, MerchantId, UserId}
import squants.market.Money

object PaymentHandling {

  def handler(configuration: ActorRef[ConfigurationMessage]): Behavior[PaymentHandlingMessage] =
    Behaviors.setup[PaymentHandlingMessage] { context =>

      // define an adapter that translates between the external protocol (the response from Configuration)
      // and the protocol of this actor
      val configurationResponseAdapter: ActorRef[Configuration.ConfigurationResponse] =
        context.messageAdapter { response => WrappedConfigurationResponse(response) }

      def handle(requests: Map[MerchantId, HandlePayment]): Behavior[PaymentHandlingMessage] =
        Behaviors.receiveMessage {
          case paymentRequest: HandlePayment =>
            configuration ! Configuration.RetrieveConfiguration(paymentRequest.merchantId, configurationResponseAdapter)
            // note: we use merchant IDs to retrieve the request state in this example to keep things simple
            // in reality, this isn't a working solution as there might be more requests per merchant ID that could be received
            // in a short time-frame and thus the state would be lost before the configuration was retrieved
            handle(requests.updated(paymentRequest.merchantId, paymentRequest))
          case wrapped: WrappedConfigurationResponse =>
            // handle the response from Configuration, which we understand since it was wrapped in a message that is part of
            // the protocol of this actor
            wrapped.response match {
              case Configuration.ConfigurationNotFound(merchantId) =>
                context.log.warning("Cannot handle request since no configuration was found for merchant", merchantId.id)
                Behaviors.same
              case Configuration.ConfigurationFound(merchantId, merchantConfiguration) =>
                requests.get(merchantId) match {
                  case Some(request) =>
                  // TODO relay the request to the proper payment processor
                    Behaviors.same
                  case None =>
                    context.log.warning("Could not find payment request for merchant id {}", merchantId.id)
                    Behaviors.same
                }
            }

        }

      handle(requests = Map.empty)
    }

  // ~~~ actor protocol

  sealed trait PaymentHandlingMessage
  case class HandlePayment(amount: Money, merchantId: MerchantId, userId: UserId) extends PaymentHandlingMessage
  case class WrappedConfigurationResponse(response: Configuration.ConfigurationResponse) extends PaymentHandlingMessage


}