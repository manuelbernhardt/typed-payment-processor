package io.bernhardt.typedpayment

import akka.actor.typed.receptionist.Receptionist.Listing
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import io.bernhardt.typedpayment.Configuration.{CreditCard, MerchantId, OrderId, TransactionId, UserId}
import squants.market.Money

/**
 * Handler for a particular payment request. It interacts with the configuration actor and a payment processing actor.
 */
object PaymentRequestHandler {
  def apply(
      orderId: OrderId,
      persistenceId: PersistenceId,
      configuration: ActorRef[Configuration.ConfigurationRequest],
      processors: Listing): Behavior[Command] = Behaviors.setup { context =>
    val configurationAdapter: ActorRef[Configuration.ConfigurationResponse] = context.messageAdapter { response =>
      AdaptedConfigurationResponse(orderId, response)
    }
    val processingAdapter: ActorRef[Processor.ProcessorResponse] = context.messageAdapter { response =>
      AdaptedProcessorResponse(orderId, response)
    }

    def commandHandler(state: State, command: Command): Effect[Event, State] = state match {
      case Empty =>
        command match {
          case HandlePaymentRequest(orderId, amount, merchantId, userId, replyTo) =>
            Effect.persist(PaymentRequestReceived(orderId, amount, merchantId, userId, replyTo)).thenRun { _ =>
              // bootstrap request handling by fetching the configuration
              configuration ! Configuration.RetrieveConfiguration(merchantId, userId, configurationAdapter)
            }
          case GracefulStop => Effect.stop[Event, State]
          case _            => Effect.unhandled
        }

      case processing: ProcessingPayment =>
        command match {
          case AdaptedConfigurationResponse(_, config: Configuration.ConfigurationFound) =>
            processRequest(config, processing.amount)
          case AdaptedConfigurationResponse(_, Configuration.ConfigurationNotFound(merchantId, userId)) =>
            Effect
              .none[Event, State]
              .thenRun { _ =>
                context.log.warn("Cannot handle request since no configuration was found for merchant %s or user %s"
                  .format(merchantId.id, userId.id))
                processing.client ! PaymentRejected("Configuration not found")
              }
              .thenStop
          case AdaptedConfigurationResponse(_, _) =>
            Effect.unhandled
          case GracefulStop => Effect.stop[Event, State]
          case _ =>
            Effect.unhandled
        }

      case processed: PaymentProcessed =>
        command match {
          case AdaptedProcessorResponse(_, Processor.RequestProcessed(transaction)) =>
            Effect
              .persist[Event, State](PaymentRequestProcessed(transaction.id))
              .thenRun { _ =>
                processed.client ! PaymentAccepted(transaction.id)
              }
              .thenStop()
          case request: HandlePaymentRequest =>
            context.log.info("Repeated payment request for order {}", orderId)
            Effect.none.thenRun { _ =>
              request.replyTo ! PaymentAccepted(processed.transactionId)
            }
          case GracefulStop => Effect.stop[Event, State]
          case _ =>
            Effect.unhandled
        }
    }

    def eventHandler(state: State, event: Event): State = state match {
      case Empty =>
        event match {
          case PaymentRequestReceived(orderId, amount, merchantId, userId, replyTo) =>
            ProcessingPayment(replyTo, orderId, amount, merchantId, userId)
          case _ => Empty
        }
      case state: ProcessingPayment =>
        event match {
          case PaymentRequestProcessed(transactionId) =>
            PaymentProcessed(state.client, transactionId, state.orderId, state.amount, state.merchantId, state.userId)
          case _ => state
        }
      case processed: PaymentProcessed => processed
    }

    def processRequest(config: Configuration.ConfigurationFound, amount: Money): Effect[Event, State] = {
      config.userConfiguration.paymentMethod match {
        case cc: CreditCard =>
          val references = processors.serviceInstances(CreditCardProcessor.Key)
          if (references.nonEmpty) {
            val reference = references.head

            Effect.none.thenRun { _ =>
              reference ! Processor.Process(amount, config.merchantConfiguration, config.userId, cc, processingAdapter)
            }
          } else {
            context.log.error("No credit card processor available")
            Effect.stop()
          }
      }
    }

    EventSourcedBehavior[Command, Event, State](
      persistenceId = persistenceId,
      emptyState = Empty,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    ).receiveSignal {
      case (state: ProcessingPayment, RecoveryCompleted) =>
        // request configuration again
        configuration ! Configuration.RetrieveConfiguration(state.merchantId, state.userId, configurationAdapter)
    }
  }

  // public protocol
  sealed trait Command {
    def orderId: OrderId
  }

  final case class HandlePaymentRequest(
      orderId: OrderId,
      amount: Money,
      merchantId: MerchantId,
      userId: UserId,
      replyTo: ActorRef[Response])
      extends Command

  final case object GracefulStop extends Command {
    // this message is intended to be sent directly from the parent shard, hence the orderId is irrelevant
    override def orderId: OrderId = OrderId("")
  }

  sealed trait Event

  final case class PaymentRequestReceived(
      orderId: OrderId,
      amount: Money,
      merchantId: MerchantId,
      userId: UserId,
      replyTo: ActorRef[Response])
      extends Event

  final case class PaymentRequestProcessed(transactionId: TransactionId) extends Event

  sealed trait State

  final case object Empty extends State

  final case class ProcessingPayment(
      client: ActorRef[Response],
      orderId: OrderId,
      amount: Money,
      merchantId: MerchantId,
      userId: UserId)
      extends State

  final case class PaymentProcessed(
      client: ActorRef[Response],
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

  private final case class AdaptedConfigurationResponse(orderId: OrderId, response: Configuration.ConfigurationResponse)
      extends InternalMessage

  private final case class AdaptedProcessorResponse(orderId: OrderId, response: Processor.ProcessorResponse)
      extends InternalMessage
}
