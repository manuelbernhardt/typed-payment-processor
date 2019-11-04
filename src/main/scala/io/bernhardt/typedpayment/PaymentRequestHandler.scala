package io.bernhardt.typedpayment

import akka.actor.typed.receptionist.Receptionist.Listing
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import io.bernhardt.typedpayment.Configuration.{ CreditCard, MerchantId, OrderId, TransactionId, UserId }
import squants.market.Money

/**
 * Handler for a particular payment request. It interacts with the configuration actor and a payment processing actor.
 */
object PaymentRequestHandler {
  def apply(
      orderId: OrderId,
      client: ActorRef[Response],
      configuration: ActorRef[Configuration.ConfigurationRequest],
      paymentProcessors: Set[Listing]): Behavior[Command] = Behaviors.setup { context =>
    val configurationAdapter: ActorRef[Configuration.ConfigurationResponse] = context.messageAdapter { response =>
      AdaptedConfigurationResponse(response)
    }
    val processingAdapter: ActorRef[Processor.ProcessorResponse] = context.messageAdapter { response =>
      AdaptedProcessorResponse(response)
    }

    def commandHandler(state: State, command: Command): Effect[Event, State] = state match {
      case Empty =>
        command match {
          case HandlePaymentRequest(amount, merchantId, userId) =>
            Effect.persist(PaymentRequestReceived(orderId, amount, merchantId, userId)).thenRun { _ =>
              // bootstrap request handling by fetching the configuration
              configuration ! Configuration.RetrieveConfiguration(merchantId, userId, configurationAdapter)
            }
          case _ => Effect.unhandled
        }

      case processing: ProcessingPayment =>
        command match {
          case AdaptedConfigurationResponse(config: Configuration.ConfigurationFound) =>
            processRequest(config, processing.amount)
          case AdaptedConfigurationResponse(Configuration.ConfigurationNotFound(merchantId, userId)) =>
            Effect
              .none[Event, State]
              .thenRun { _ =>
                context.log.warn("Cannot handle request since no configuration was found for merchant %s or user %s"
                  .format(merchantId.id, userId.id))
                client ! PaymentRejected("Configuration not found")
              }
              .thenStop
          case ConfigurationFailure(exception) =>
            Effect
              .none[Event, State]
              .thenRun { _ =>
                context.log.warn("Could not retrieve configuration", exception)
                client ! PaymentRejected("Configuration failed")
              }
              .thenStop()
          case AdaptedConfigurationResponse(_) =>
            Effect.unhandled
          case _ =>
            Effect.unhandled
        }

      case processed: PaymentProcessed =>
        command match {
          case AdaptedProcessorResponse(Processor.RequestProcessed(transaction)) =>
            Effect
              .persist[Event, State](PaymentRequestProcessed(transaction.id))
              .thenRun { _ =>
                client ! PaymentAccepted(transaction.id)
              }
              .thenStop()
          case _: HandlePaymentRequest =>
            context.log.info("Repeated payment request for order {}", orderId)
            Effect.none.thenRun { _ =>
              client ! PaymentAccepted(processed.transactionId)
            }
          case _ =>
            Effect.unhandled
        }
    }

    def eventHandler(state: State, event: Event): State = state match {
      case Empty =>
        event match {
          case PaymentRequestReceived(orderId, amount, merchantId, userId) =>
            ProcessingPayment(orderId, amount, merchantId, userId)
          case _ => Empty
        }
      case state: ProcessingPayment =>
        event match {
          case PaymentRequestProcessed(transactionId) =>
            PaymentProcessed(transactionId, state.orderId, state.amount, state.merchantId, state.userId)
          case _ => state
        }
      case processed: PaymentProcessed => processed
    }

    def processRequest(config: Configuration.ConfigurationFound, amount: Money): Effect[Event, State] = {
      config.userConfiguration.paymentMethod match {
        case cc: CreditCard =>
          paymentProcessors.find(_.isForKey(CreditCardProcessor.Key)) match {
            case Some(listing) =>
              val reference = listing.serviceInstances(CreditCardProcessor.Key).head
              Effect.none.thenRun { _ =>
                reference ! Processor
                  .Process(amount, config.merchantConfiguration, config.userId, cc, processingAdapter)
              }
            case None =>
              context.log.error("No credit card processor available")
              Effect.stop()
          }
      }
    }

    EventSourcedBehavior[Command, Event, State](PersistenceId(orderId.id), Empty, commandHandler, eventHandler)
  }

  // public protocol
  sealed trait Command
  final case class HandlePaymentRequest(amount: Money, merchantId: MerchantId, userId: UserId) extends Command

  sealed trait Event
  final case class PaymentRequestReceived(orderId: OrderId, amount: Money, merchantId: MerchantId, userId: UserId)
      extends Event
  final case class PaymentRequestProcessed(transactionId: TransactionId) extends Event

  sealed trait State
  final case object Empty extends State
  final case class ProcessingPayment(orderId: OrderId, amount: Money, merchantId: MerchantId, userId: UserId)
      extends State
  final case class PaymentProcessed(
      transactionId: TransactionId,
      orderId: OrderId,
      amount: Money,
      merchantId: MerchantId,
      userId: UserId)
      extends State

  sealed trait Response
  final case class PaymentAccepted(transactionId: TransactionId) extends Response
  final case class PaymentRejected(reason: String) extends Response

  // internal protocol
  sealed trait InternalMessage extends Command
  private final case class AdaptedConfigurationResponse(response: Configuration.ConfigurationResponse)
      extends InternalMessage
  private final case class AdaptedProcessorResponse(response: Processor.ProcessorResponse) extends InternalMessage
  private final case class ConfigurationFailure(exception: Throwable) extends InternalMessage
}
