package io.bernhardt.typedpayment

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import io.bernhardt.typedpayment.Configuration.{ConfigurationMessage, MerchantId, UserId}
import squants.market.Money

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object PaymentHandling {

  def handler(configuration: ActorRef[ConfigurationMessage]): Behavior[PaymentHandlingMessage] =
    Behaviors.setup[PaymentHandlingMessage] { context =>
      Behaviors.receiveMessage {
        case paymentRequest: HandlePayment =>
          // define the timeout after which the ask request has failed
          implicit val timeout: Timeout = 1.second

          def buildConfigurationRequest(ref: ActorRef[Configuration.ConfigurationResponse]) =
            Configuration.RetrieveConfiguration(paymentRequest.merchantId, ref)

          context.ask(configuration)(buildConfigurationRequest) {
            case Success(response: Configuration.ConfigurationResponse) => AdaptedConfigurationResponse(response, paymentRequest)
            case Failure(exception) => ConfigurationFailure(exception)
          }

          Behaviors.same
        case AdaptedConfigurationResponse(Configuration.ConfigurationNotFound(merchantId), _) =>
          context.log.warning("Cannot handle request since no configuration was found for merchant", merchantId.id)
          Behaviors.same
        case AdaptedConfigurationResponse(Configuration.ConfigurationFound(merchantId, merchantConfiguration), request) =>
          // TODO relay the request to the proper payment processor
          Behaviors.unhandled
        case ConfigurationFailure(exception) =>
          context.log.warning(exception, "Could not retrieve configuration")
          Behaviors.same
      }
    }

  // ~~~ actor protocol
  sealed trait PaymentHandlingMessage
  case class HandlePayment(amount: Money, merchantId: MerchantId, userId: UserId) extends PaymentHandlingMessage

  // ~~~ internal protocol
  case class AdaptedConfigurationResponse(response: Configuration.ConfigurationResponse, request: HandlePayment) extends PaymentHandlingMessage
  case class ConfigurationFailure(exception: Throwable) extends PaymentHandlingMessage


}