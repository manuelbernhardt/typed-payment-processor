package io.bernhardt.typedpayment

import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.Behaviors
import io.bernhardt.typedpayment.Configuration.UserId
import io.bernhardt.typedpayment.Processor.ProcessorRequest
import squants.market.Money

object CreditCardProcessor {

  def process: Behavior[ProcessorRequest] = Behaviors.setup { context =>
    // register with the Receptionist which makes this actor discoverable
    context.system.receptionist ! Receptionist.Register(Key, context.self)
    // TODO implement the actual behaviour
    Behaviors.unhandled
  }

  val Key: ServiceKey[ProcessorRequest] = ServiceKey("creditCardProcessor")

}

object Processor {
  sealed trait ProcessorRequest
  case class Process(amount: Money, merchantConfiguration: Configuration, userId: UserId) extends ProcessorRequest
}