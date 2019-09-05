package io.bernhardt.typedpayment

import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.Behaviors
import io.bernhardt.typedpayment.Configuration.{CreditCard, CreditCardId, MerchantConfiguration, PaymentMethod, UserId}
import io.bernhardt.typedpayment.Processor.ProcessorRequest
import squants.market.Money

object CreditCardProcessor {

  def process: Behavior[ProcessorRequest] = Behaviors.setup { context =>
    // register with the Receptionist which makes this actor discoverable
    context.system.receptionist ! Receptionist.Register(Key, context.self)

    // dummy storage reference
    val storage = new FailingCreditCardStorage

    Behaviors.receiveMessage {
      case Processor.Process(amount, merchantConfiguration, userId, paymentMethod: CreditCard) =>

        // this will fail since the storage is unavailable
        storage.findCreditCard(paymentMethod.storageId)

        // TODO here should come the actual implementation
        Behaviors.unhandled
    }

  }

  val Key: ServiceKey[ProcessorRequest] = ServiceKey("creditCardProcessor")

}

object Processor {
  sealed trait ProcessorRequest
  case class Process(amount: Money, merchantConfiguration: MerchantConfiguration, userId: UserId, paymentMethod: PaymentMethod) extends ProcessorRequest
}

trait CreditCardStorage {
  def findCreditCard(userId: CreditCardId): Option[StoredCreditCard]
}
class FailingCreditCardStorage extends CreditCardStorage {
  override def findCreditCard(userId: CreditCardId): Option[StoredCreditCard] = {
    throw new StorageFailedException
  }
}

class StorageFailedException extends RuntimeException

case class StoredCreditCard(id: CreditCardId)

