package io.bernhardt.typedpayment

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import io.bernhardt.typedpayment.Configuration._

// the AbstractBehavior trait is the entry point for using the object-oriented style API
class Configuration(context: ActorContext[ConfigurationRequest]) extends AbstractBehavior[ConfigurationRequest](context) {

  // the mutable state here holds the configuration values of each merchant we know about
  var merchantConfigurations: Map[MerchantId, MerchantConfiguration] = Map.empty
  var userConfigurations: Map[UserId, UserConfiguration] = Map.empty

  // the onMessage method defines the initial behavior applied to a message upon reception
  override def onMessage(msg: ConfigurationRequest): Behavior[ConfigurationRequest] = msg match {
    case RetrieveConfiguration(merchantId, userId, replyTo) =>
      (merchantConfigurations.get(merchantId), userConfigurations.get(userId)) match {
        case (Some(merchantConfiguration), Some(userConfiguration)) =>
          // reply to the sender using the fire-and-forget paradigm
          replyTo ! ConfigurationFound(merchantId, userId, merchantConfiguration, userConfiguration)
        case _ =>
          // reply to the sender using the fire-and-forget paradigm
          replyTo ! ConfigurationNotFound(merchantId, userId)
      }
      // lastly, return the Behavior to be applied to the next received message
      // in this case, that's just the same Behavior as we already have
      this
    case StoreMerchantConfiguration(merchantId, configuration, replyTo) =>
      merchantConfigurations += merchantId -> configuration
      replyTo ! MerchantConfigurationStored(merchantId)
      this
    case StoreUserConfiguration(userId, configuration, replyTo) =>
      userConfigurations += userId -> configuration
      replyTo ! UserConfigurationStored(userId)
      this
  }
}

object Configuration {

  def apply(): Behavior[ConfigurationRequest] = Behaviors.setup(context => new Configuration(context))

  case class MerchantId(id: String) extends AnyVal
  case class UserId(id: String) extends AnyVal
  case class BankIdentifier(id: String) extends AnyVal
  case class CreditCardId(id: String) extends AnyVal
  case class TransactionId(id: String) extends AnyVal

  sealed trait PaymentMethod
  case class CreditCard(storageId: CreditCardId) extends PaymentMethod

  case class MerchantConfiguration(merchantId: MerchantId, bankIdentifier: BankIdentifier)
  case class UserConfiguration(paymentMethod: PaymentMethod)

  sealed trait ConfigurationRequest
  final case class RetrieveConfiguration(merchantId: MerchantId, userId: UserId, replyTo: ActorRef[ConfigurationResponse]) extends ConfigurationRequest
  final case class StoreMerchantConfiguration(merchantId: MerchantId, configuration: MerchantConfiguration, replyTo: ActorRef[ConfigurationResponse]) extends ConfigurationRequest
  final case class StoreUserConfiguration(userId: UserId, configuration: UserConfiguration, replyTo: ActorRef[ConfigurationResponse]) extends ConfigurationRequest

  sealed trait ConfigurationResponse
  final case class ConfigurationFound(merchantId: MerchantId, userId: UserId, merchantConfiguration: MerchantConfiguration, userConfiguration: UserConfiguration) extends ConfigurationResponse
  final case class ConfigurationNotFound(merchanId: MerchantId, userId: UserId) extends ConfigurationResponse
  final case class MerchantConfigurationStored(merchantId: MerchantId) extends ConfigurationResponse
  final case class UserConfigurationStored(userId: UserId) extends ConfigurationResponse

}
