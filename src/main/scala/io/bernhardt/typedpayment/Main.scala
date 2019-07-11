package io.bernhardt.typedpayment

import akka.actor.typed.ActorSystem

/**
  * Entry point of the Payment Processor application
  */
object Main extends App {

  override def main(args: Array[String]): Unit =
    ActorSystem[Nothing](PaymentProcessor.payment(), "typed-payment-processor")

}