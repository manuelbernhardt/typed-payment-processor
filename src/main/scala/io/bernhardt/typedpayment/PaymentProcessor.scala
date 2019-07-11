package io.bernhardt.typedpayment

import akka.actor.typed.scaladsl.Behaviors

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

  def payment() = Behaviors.setup[Nothing] { context =>
    context.log.info("Typed Payment Processor started")
    context.spawn(Configuration(), "config")
    Behaviors.empty
  }

}
