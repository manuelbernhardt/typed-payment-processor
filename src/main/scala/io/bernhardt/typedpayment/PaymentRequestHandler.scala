package io.bernhardt.typedpayment

import akka.actor.typed.receptionist.Receptionist.Listing
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import io.bernhardt.typedpayment.Configuration.{CreditCard, MerchantId, TransactionId, UserId}
import squants.market.Money

/**
 * Handler for a particular payment request. It interacts with the configuration actor and a payment processing actor.
 */
object PaymentRequestHandler {

  def apply(client: ActorRef[Response],
            configuration: ActorRef[Configuration.ConfigurationRequest],
            paymentProcessors: Set[Listing]): Behavior[Command] = Behaviors.setup { context =>

      val configurationAdapter: ActorRef[Configuration.ConfigurationResponse] = context.messageAdapter { response =>
        AdaptedConfigurationResponse(response)
      }
      val processingAdapter: ActorRef[Processor.ProcessorResponse] = context.messageAdapter { response =>
        AdaptedProcessorResponse(response)
      }

      def handlePaymentRequest: Behavior[Command] =
        Behaviors.receiveMessage {
          case request: HandlePaymentRequest =>
            // bootstrap request handling by fetching the configuration
            configuration ! Configuration.RetrieveConfiguration(request.merchantId, request.userId, configurationAdapter)
            handleConfigurationResponse(request)
          case _ => Behaviors.unhandled
        }

      def handleConfigurationResponse(request: HandlePaymentRequest): Behavior[Command] =
        Behaviors.receiveMessage {
          case AdaptedConfigurationResponse(config: Configuration.ConfigurationFound) =>
            processRequest(config, request.amount)
          case AdaptedConfigurationResponse(Configuration.ConfigurationNotFound(merchantId, userId)) =>
            context.log.warn("Cannot handle request since no configuration was found for merchant %s or user %s".format(merchantId.id, userId.id))
            client ! PaymentRejected("Configuration not found")
            Behaviors.stopped
          case ConfigurationFailure(exception) =>
            context.log.warn("Could not retrieve configuration", exception)
            client ! PaymentRejected("Configuration failed")
            Behaviors.stopped
          case AdaptedConfigurationResponse(_) =>
            Behaviors.unhandled
          case _ => Behaviors.unhandled
        }

      def handleProcessorResponse: Behavior[Command] =
        Behaviors.receiveMessage {
          case AdaptedProcessorResponse(Processor.RequestProcessed(transaction)) =>
            client ! PaymentAccepted(transaction.id)
            // we've done our job, now shut down
            Behaviors.stopped
          case _ => Behaviors.unhandled
        }

      def processRequest(config: Configuration.ConfigurationFound, amount: Money): Behavior[Command] = {
        config.userConfiguration.paymentMethod match {
          case cc: CreditCard =>
            paymentProcessors.find(_.isForKey(CreditCardProcessor.Key)) match {
              case Some(listing) =>
                val reference = listing.serviceInstances(CreditCardProcessor.Key).head
                reference ! Processor.Process(amount, config.merchantConfiguration, config.userId, cc, processingAdapter)
                handleProcessorResponse
              case None =>
                context.log.error("No credit card processor available")
                Behaviors.stopped
            }
        }
      }

      // initial behavior
      handlePaymentRequest
    }

  // public protocol
  sealed trait Command
  case class HandlePaymentRequest(id: PaymentRequestId, amount: Money, merchantId: MerchantId, userId: UserId) extends Command
  case class PaymentRequestId(id: String) extends AnyVal

  sealed trait Response
  case class PaymentAccepted(transactionId: TransactionId) extends Response
  case class PaymentRejected(reason: String) extends Response

  // internal protocol
  sealed trait InternalMessage extends Command
  case class AdaptedConfigurationResponse(response: Configuration.ConfigurationResponse) extends InternalMessage
  case class AdaptedProcessorResponse(response: Processor.ProcessorResponse) extends InternalMessage
  case class ConfigurationFailure(exception: Throwable) extends InternalMessage

}
