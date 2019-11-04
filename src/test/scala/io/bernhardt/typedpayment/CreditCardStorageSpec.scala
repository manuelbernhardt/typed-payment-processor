package io.bernhardt.typedpayment

import java.io.File

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import io.bernhardt.typedpayment.Configuration.{ CreditCardId, UserId }
import io.bernhardt.typedpayment.CreditCardStorage.CreditCardFound
import org.scalatest.{ BeforeAndAfterAll, WordSpecLike }

import scala.reflect.io.Directory

class CreditCardStorageSpec extends ScalaTestWithActorTestKit with WordSpecLike with BeforeAndAfterAll {
  "The Credit Card Storage" should {
    "Add cards" in {
      val probe = createTestProbe[CreditCardStorage.AddCreditCardResult]()
      val storage = spawn(CreditCardStorage())

      storage ! CreditCardStorage.AddCreditCard(UserId("bob"), "1234", probe.ref)
      probe.expectMessageType[CreditCardStorage.Added]
    }

    "Not add duplicate cards" in {
      val probe = createTestProbe[CreditCardStorage.AddCreditCardResult]()
      val storage = spawn(CreditCardStorage())

      storage ! CreditCardStorage.AddCreditCard(UserId("bob"), "4321", probe.ref)
      probe.expectMessageType[CreditCardStorage.Added]
      storage ! CreditCardStorage.AddCreditCard(UserId("bob"), "4321", probe.ref)
      probe.expectMessageType[CreditCardStorage.Duplicate.type]
    }

    "Find cards by id" in {
      val probe = createTestProbe[CreditCardStorage.AddCreditCardResult]()
      val storage = spawn(CreditCardStorage())
      storage ! CreditCardStorage.AddCreditCard(UserId("bob"), "1111", probe.ref)
      val added = probe.expectMessageType[CreditCardStorage.Added]

      val lookupProbe = createTestProbe[CreditCardStorage.FindCreditCardResult]()
      storage ! CreditCardStorage.FindById(added.id, lookupProbe.ref)
      val found = lookupProbe.expectMessageType[CreditCardFound]
      found.card.last4Digits shouldBe "1111"
    }

    "Not find unknown cards by id" in {
      val lookupProbe = createTestProbe[CreditCardStorage.FindCreditCardResult]()
      val storage = spawn(CreditCardStorage())
      storage ! CreditCardStorage.FindById(CreditCardId("42"), lookupProbe.ref)
      lookupProbe.expectMessageType[CreditCardStorage.CreditCardNotFound]
    }
  }

  override protected def afterAll(): Unit = {
    val dir = new Directory(new File(system.settings.config.getString("akka.persistence.journal.leveldb.dir")))
    dir.deleteRecursively()
  }
}
