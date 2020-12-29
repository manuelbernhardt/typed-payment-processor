package io.bernhardt.typedpayment

import akka.actor.typed.ActorSystem

/**
 * Entry point of the Payment Processor application
 */
object Main {
  def main(args: Array[String]): Unit =
    ActorSystem[Nothing](PaymentProcessor(), "typed-payment-processor")
}
