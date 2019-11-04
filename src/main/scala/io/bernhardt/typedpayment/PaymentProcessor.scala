package io.bernhardt.typedpayment

import akka.actor.typed.{ ChildFailed, SupervisorStrategy }
import akka.actor.typed.scaladsl.Behaviors

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
      context.spawn(Configuration(), "config")

      val supervisedCreditCardProcessor = Behaviors
        .supervise(CreditCardProcessor.apply)
        .onFailure[RuntimeException](
          SupervisorStrategy.restartWithBackoff(minBackoff = 5.seconds, maxBackoff = 1.minute, randomFactor = 0.2))

      val processor = context.spawn(supervisedCreditCardProcessor, "creditCardProcessor")

      // watch the child actor by passing its reference
      context.watch(processor)

      Behaviors.receiveSignal[Nothing] {
        case (_, ChildFailed(ref, cause)) =>
          context.log.warn("The child actor %s failed because %s".format(ref, cause.getMessage))
          Behaviors.same[Nothing]
      }
    }
}
