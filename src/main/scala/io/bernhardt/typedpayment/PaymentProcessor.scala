package io.bernhardt.typedpayment

import akka.actor.typed.{ActorRef, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.{ClusterSingleton, SingletonActor}

import scala.concurrent.duration._

/**
 * Top-level actor of the Payment Processor implementation.
 *
 * It is responsible for supervising the various components of the processor, which are planned to be the following:
 *
 * - API
 * - Payment Handling
 * - Configuration
 * - Payment Processors
 *   - Credit Card Payment Processor
 *   - Google Pay Payment Processor
 *   - ...
 *
 * An example request flow would be:
 *
 * - call API for credit card payment
 * - API               ->  Payment Handling:               authorization request
 * - Payment Handling  ->  Configuration:                  lookup merchant configuration
 * - Payment Handling  <-  Configuration:                  reference to payment processing service, additional data
 * - Payment Handling  ->  Credit Card Payment Processing: issue authorization using additional data
 * - Payment Handling  <-  Credit Card Payment Processing: authorization success
 * - Payment Handling  <-  API:                            authorization response
 *
 */
object PaymentProcessor {
  def apply() =
    Behaviors.setup[Nothing] { context =>
      context.log.info("Typed Payment Processor started")

      val configuration: ActorRef[Configuration.ConfigurationRequest] =
        ClusterSingleton(context.system).init(SingletonActor(
          Configuration(), "config"
        ))

      val creditCardStorage = ClusterSingleton(context.system).init(SingletonActor(
        CreditCardStorage(), "credit-card-storage"
      ))

      val supervisedCreditCardProcessor = Behaviors
        .supervise(CreditCardProcessor(creditCardStorage))
        .onFailure[Exception](
          SupervisorStrategy.restartWithBackoff(minBackoff = 5.seconds, maxBackoff = 1.minute, randomFactor = 0.2))

      for (i <- 1 to 10) {
        context.spawn(supervisedCreditCardProcessor, s"creditCardProcessor-$i")
      }

      context.spawn(PaymentHandling(configuration),"handling")

      Behaviors.empty
    }
}
