package io.bernhardt.typedpayment

import akka.actor.typed.receptionist.Receptionist.Listing
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import io.bernhardt.typedpayment.Configuration.{ConfigurationMessage, CreditCard, MerchantId, UserId}
import squants.market.Money

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object PaymentHandling {

  def apply(configuration: ActorRef[ConfigurationMessage], paymentProcessors: Set[Listing]): Behavior[PaymentHandlingMessage] =
    Behaviors.setup[PaymentHandlingMessage] { context =>

      // subscribe to the processor reference updates we're interested in
      val listingAdapter: ActorRef[Receptionist.Listing] = context.messageAdapter { listing =>
        AddProcessorReference(listing)
      }
      context.system.receptionist ! Receptionist.Subscribe(CreditCardProcessor.Key, listingAdapter)

      val processingAdapter: ActorRef[Processor.ProcessorResponse] = context.messageAdapter { response =>
        AdaptedProcessorResponse(response)
      }

      def handlePaymentRequest(paymentRequest: HandlePayment): Behavior[PaymentHandlingMessage] = {
        // define the timeout after which the ask request has failed
        implicit val timeout: Timeout = 1.second

        def buildConfigurationRequest(ref: ActorRef[Configuration.ConfigurationResponse]) =
          Configuration.RetrieveConfiguration(paymentRequest.merchantId, paymentRequest.userId, ref)

        context.ask(configuration, buildConfigurationRequest) {
          case Success(response: Configuration.ConfigurationResponse) => AdaptedConfigurationResponse(response, paymentRequest)
          case Failure(exception) => ConfigurationFailure(exception)
        }
        Behaviors.same
      }

      def handleConfigurationFound(config: Configuration.ConfigurationFound, request: HandlePayment): Behavior[PaymentHandlingMessage] = {
        config.userConfiguration.paymentMethod match {
          case cc: CreditCard =>
            paymentProcessors.find(_.isForKey(CreditCardProcessor.Key)) match {
              case Some(listing) =>
                val reference = listing.serviceInstances(CreditCardProcessor.Key).head
                reference ! Processor.Process(request.amount, config.merchantConfiguration, config.userId, cc, processingAdapter)
                Behaviors.same
              case None =>
                context.log.error("No credit card processor found")
            }
            Behaviors.unhandled
        }
      }

      Behaviors.receiveMessage {
        case AddProcessorReference(listing) =>
          apply(configuration, paymentProcessors + listing)
        case paymentRequest: HandlePayment =>
          handlePaymentRequest(paymentRequest)
        case AdaptedConfigurationResponse(config: Configuration.ConfigurationFound, request) =>
          handleConfigurationFound(config, request)
        case AdaptedProcessorResponse(Processor.RequestProcessed(transaction)) =>
          // TODO reply to the original sender
          Behaviors.same
        case AdaptedConfigurationResponse(Configuration.ConfigurationNotFound(merchantId, userId), _) =>
          context.log.warn("Cannot handle request since no configuration was found for merchant %s or user %s".format(merchantId.id, userId.id))
          Behaviors.same
        case AdaptedConfigurationResponse(_: Configuration.UserConfigurationStored, _) =>
          Behaviors.same
        case AdaptedConfigurationResponse(_: Configuration.MerchantConfigurationStored, _) =>
          Behaviors.same
        case ConfigurationFailure(exception) =>
          context.log.warn("Could not retrieve configuration", exception)
          Behaviors.same
      }
    }

  // ~~~ actor protocol
  sealed trait PaymentHandlingMessage
  case class HandlePayment(amount: Money, merchantId: MerchantId, userId: UserId) extends PaymentHandlingMessage

  // ~~~ internal protocol
  sealed trait InternalMessage extends PaymentHandlingMessage
  case class AdaptedConfigurationResponse(response: Configuration.ConfigurationResponse, request: HandlePayment) extends InternalMessage
  case class AdaptedProcessorResponse(response: Processor.ProcessorResponse) extends InternalMessage
  case class ConfigurationFailure(exception: Throwable) extends InternalMessage
  case class AddProcessorReference(listing: Receptionist.Listing) extends InternalMessage



}