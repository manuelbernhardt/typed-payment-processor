package io.bernhardt.typedpayment

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import io.bernhardt.typedpayment.Configuration.{BankIdentifier, ConfigurationResponse, CreditCard, CreditCardId, MerchantConfiguration, MerchantId, UserConfiguration, UserId}
import org.scalatest.WordSpecLike

class ConfigurationSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  "The Configuration actor" should {

    "not find a configuration for an unknown merchant" in {
      // define a probe which allows it to easily send messages
      val probe = createTestProbe[ConfigurationResponse]()

      // spawn a new Configuration actor as child of the TestKit's guardian actor
      val configurationActor = spawn(Configuration())

      // send a message to the actor under test with the probe's reference as sender
      configurationActor ! Configuration.RetrieveConfiguration(MerchantId("unknownMerchant"), UserId("unknownUser"), probe.ref)

      // expect a certain type of message as response. there's many different ways to retrieve
      // or to expect messages
      val response = probe.expectMessageType[Configuration.ConfigurationNotFound]
      response.merchanId shouldBe MerchantId("unknownMerchant")
      response.userId shouldBe UserId("unknownUser")
    }

    "find a merchant configuration for a known merchant and user" in {
      val probe = createTestProbe[ConfigurationResponse]()
      val configurationActor = spawn(Configuration())

      configurationActor ! Configuration.StoreMerchantConfiguration(MerchantId("knownMerchant"), MerchantConfiguration(BankIdentifier("id")), probe.ref)
      probe.expectMessageType[Configuration.MerchantConfigurationStored]

      configurationActor ! Configuration.StoreUserConfiguration(UserId("knownUser"), UserConfiguration(CreditCard(CreditCardId("id"))), probe.ref)
      probe.expectMessageType[Configuration.UserConfigurationStored]

      configurationActor ! Configuration.RetrieveConfiguration(MerchantId("knownMerchant"), UserId("knownUser"), probe.ref)
      val response = probe.expectMessageType[Configuration.ConfigurationFound]
      response.merchantId shouldBe MerchantId("knownMerchant")
      response.userId shouldBe UserId("knownUser")
    }


  }

}
