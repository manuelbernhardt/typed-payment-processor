package io.bernhardt.typedpayment

import akka.actor.typed.scaladsl._
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.HashCodeNoEnvelopeMessageExtractor
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import io.bernhardt.typedpayment.Configuration.{ConfigurationRequest, MerchantId, OrderId, UserId}
import squants.market.Money

/**
 * Keeps track of available payment processors and delegates incoming requests to a dedicated sharded entity
 */
object PaymentHandling {
  def apply(configuration: ActorRef[ConfigurationRequest]): Behavior[Command] =
    Behaviors.setup[Command] { context =>
      // initialize the shading extension
      val sharding = ClusterSharding(context.system)

      // define a message extractor that knows how to retrieve the entityId from a message
      // we plan on deploying on a 3-node cluster, as a rule of thumb there should be 10 times as many
      // shards as there are nodes, hence the numberOfShards value of 30
      val messageExtractor =
        new HashCodeNoEnvelopeMessageExtractor[PaymentRequestHandler.Command](numberOfShards = 30) {
          override def entityId(message: PaymentRequestHandler.Command): String = message.orderId.id
        }

      // initialize the group router for credit card processors
      val creditCardProcessorRouter = context.spawn(
        Routers.group(CreditCardProcessor.Key),
        "creditCardProcessors"
      )

      // initialize the shard region
      val shardRegion: ActorRef[PaymentRequestHandler.Command] =
        sharding.init(
          Entity(PaymentRequestHandlerTypeKey) { context =>
            PaymentRequestHandler(
              OrderId(context.entityId),
              PersistenceId(context.entityTypeKey.name, context.entityId),
              configuration,
              creditCardProcessorRouter)
          }.withMessageExtractor(messageExtractor)
            // custom stop message to allow for graceful shutdown
            // this is especially important for persistent actors, as the default is PoisonPill,
            // which doesn't allow the actor to flush all messages in flight to the journal
            .withStopMessage(PaymentRequestHandler.GracefulStop))

          Behaviors.receiveMessage {
            case paymentRequest: HandlePayment =>
              shardRegion ! PaymentRequestHandler.HandlePaymentRequest(
                paymentRequest.orderId,
                paymentRequest.amount,
                paymentRequest.merchantId,
                paymentRequest.userId,
                paymentRequest.sender)
              Behaviors.same
            case _ => Behaviors.unhandled
          }

    }

  // ~~~ public protocol
  sealed trait Command

  case class HandlePayment(
      orderId: OrderId,
      amount: Money,
      merchantId: MerchantId,
      userId: UserId,
      sender: ActorRef[PaymentRequestHandler.Response])
      extends Command

  val PaymentRequestHandlerTypeKey = EntityTypeKey[PaymentRequestHandler.Command]("PaymentRequestHandler")
}
